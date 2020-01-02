/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.tomcat;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;

import javax.annotation.Nullable;

import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.ServerInfo;
import org.apache.coyote.Adapter;
import org.apache.coyote.InputBuffer;
import org.apache.coyote.OutputBuffer;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.util.AppRootFinder;
import com.linecorp.armeria.internal.tomcat.TomcatVersion;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;

/**
 * An {@link HttpService} that dispatches its requests to a web application running in an embedded
 * <a href="https://tomcat.apache.org/">Tomcat</a>.
 *
 * @see TomcatServiceBuilder
 */
public abstract class TomcatService implements HttpService {

    static final Logger logger = LoggerFactory.getLogger(TomcatService.class);

    private static final MethodHandle INPUT_BUFFER_CONSTRUCTOR;
    private static final MethodHandle OUTPUT_BUFFER_CONSTRUCTOR;
    static final Class<?> PROTOCOL_HANDLER_CLASS;

    static {
        final String prefix = TomcatService.class.getPackage().getName() + '.';
        final ClassLoader classLoader = TomcatService.class.getClassLoader();
        final Class<?> inputBufferClass;
        final Class<?> outputBufferClass;
        final Class<?> protocolHandlerClass;
        try {
            if (TomcatVersion.major() < 8 || TomcatVersion.major() == 8 && TomcatVersion.minor() < 5) {
                inputBufferClass = Class.forName(prefix + "Tomcat80InputBuffer", true, classLoader);
                outputBufferClass = Class.forName(prefix + "Tomcat80OutputBuffer", true, classLoader);
                protocolHandlerClass = Class.forName(prefix + "Tomcat80ProtocolHandler", true, classLoader);
            } else {
                inputBufferClass = Class.forName(prefix + "Tomcat90InputBuffer", true, classLoader);
                outputBufferClass = Class.forName(prefix + "Tomcat90OutputBuffer", true, classLoader);
                protocolHandlerClass = Class.forName(prefix + "Tomcat90ProtocolHandler", true, classLoader);
            }

            INPUT_BUFFER_CONSTRUCTOR = MethodHandles.lookup().findConstructor(
                    inputBufferClass, MethodType.methodType(void.class, HttpData.class));
            OUTPUT_BUFFER_CONSTRUCTOR = MethodHandles.lookup().findConstructor(
                    outputBufferClass, MethodType.methodType(void.class, Queue.class));
            PROTOCOL_HANDLER_CLASS = protocolHandlerClass;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "could not find the matching classes for Tomcat version " + ServerInfo.getServerNumber() +
                    "; using a wrong armeria-tomcat JAR?", e);
        }

        if (TomcatVersion.major() >= 9) {
            try {
                final Class<?> initClass =
                        Class.forName(prefix + "ConfigFileLoaderInitializer", true, classLoader);
                MethodHandles.lookup()
                             .findStatic(initClass, "init", MethodType.methodType(void.class))
                             .invoke();
            } catch (Throwable cause) {
                logger.debug("Failed to initialize Tomcat ConfigFileLoader.source:", cause);
            }
        }
    }

    private static final ResponseHeaders INVALID_AUTHORITY_HEADERS =
            ResponseHeaders.of(HttpStatus.BAD_REQUEST,
                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
    private static final HttpData INVALID_AUTHORITY_DATA =
            HttpData.ofUtf8(HttpStatus.BAD_REQUEST + "\nInvalid authority");

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService of(File docBase) {
        return builder(docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService of(Path docBase) {
        return builder(docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService of(File rootDir, String relativeDocBase) {
        return builder(rootDir, relativeDocBase).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService of(Path rootDir, String relativeDocBase) {
        return builder(rootDir, relativeDocBase).build();
    }

    /**
     * Creates a new {@link TomcatService} from an existing {@link Tomcat} instance.
     * If the specified {@link Tomcat} instance is not configured properly, the returned {@link TomcatService}
     * may respond with '503 Service Not Available' error.
     *
     * @return a new {@link TomcatService}, which will not manage the provided {@link Tomcat} instance.
     */
    public static TomcatService of(Tomcat tomcat) {
        return new UnmanagedTomcatService(requireNonNull(tomcat, "tomcat"));
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     *
     * @return a new {@link TomcatService}, which will not manage the provided {@link Connector} instance.
     */
    public static TomcatService of(Connector connector) {
        return new UnmanagedTomcatService(requireNonNull(connector, "connector"), null);
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     *
     * @return a new {@link TomcatService}, which will not manage the provided {@link Connector} instance.
     */
    public static TomcatService of(Connector connector, String hostname) {
        requireNonNull(connector, "connector");
        requireNonNull(hostname, "hostname");
        return new UnmanagedTomcatService(connector, hostname);
    }

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the specified document base,
     * which can be a directory or a JAR/WAR file.
     */
    public static TomcatServiceBuilder builder(File docBase) {
        return builder(requireNonNull(docBase, "docBase").toPath());
    }

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the specified document base,
     * which can be a directory or a JAR/WAR file.
     */
    public static TomcatServiceBuilder builder(Path docBase) {
        final Path absoluteDocBase = requireNonNull(docBase, "docBase").toAbsolutePath();
        if (TomcatUtil.isZip(absoluteDocBase)) {
            return new TomcatServiceBuilder(absoluteDocBase, "/");
        }

        checkArgument(Files.isDirectory(absoluteDocBase),
                      "docBase: %s (expected: a directory, WAR or JAR)", docBase);

        return new TomcatServiceBuilder(absoluteDocBase, null);
    }

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the specified document base,
     * which can be a directory or a JAR/WAR file.
     */
    public static TomcatServiceBuilder builder(File rootDirOrDocBase, String relativePath) {
        return builder(requireNonNull(rootDirOrDocBase, "rootDirOrDocBase").toPath(), relativePath);
    }

    /**
     * Creates a new {@link TomcatServiceBuilder} with the web application at the specified document base,
     * which can be a directory or a JAR/WAR file.
     */
    public static TomcatServiceBuilder builder(Path rootDirOrDocBase, String relativePath) {
        requireNonNull(rootDirOrDocBase, "rootDirOrDocBase");
        requireNonNull(relativePath, "relativePath");
        final Path absoluteRootDirOrDocBase = rootDirOrDocBase.toAbsolutePath();
        if (TomcatUtil.isZip(absoluteRootDirOrDocBase)) {
            return new TomcatServiceBuilder(absoluteRootDirOrDocBase, normalizeJarRoot(relativePath));
        }

        checkArgument(Files.isDirectory(absoluteRootDirOrDocBase),
                      "rootDirOrDocBase: %s (expected: a directory, WAR or JAR)", rootDirOrDocBase);

        final Path rootDir = fileSystemDocBase(rootDirOrDocBase, relativePath);
        checkArgument(Files.isDirectory(rootDir),
                      "relativePath: %s (expected: a directory)", relativePath);

        return new TomcatServiceBuilder(rootDir, null);
    }

    private static String normalizeJarRoot(@Nullable String jarRoot) {
        if (jarRoot == null || jarRoot.isEmpty() || "/".equals(jarRoot)) {
            return "/";
        }

        if (!jarRoot.startsWith("/")) {
            jarRoot = '/' + jarRoot;
        }

        if (jarRoot.endsWith("/")) {
            jarRoot = jarRoot.substring(0, jarRoot.length() - 1);
        }

        return jarRoot;
    }

    private static Path fileSystemDocBase(Path rootDir, String relativePath) {
        // Append the specified docBase to the root directory to build the actual docBase on file system.
        String fileSystemDocBase = rootDir.toString();
        relativePath = relativePath.replace('/', File.separatorChar);
        if (fileSystemDocBase.endsWith(File.separator)) {
            if (relativePath.startsWith(File.separator)) {
                fileSystemDocBase += relativePath.substring(1);
            } else {
                fileSystemDocBase += relativePath;
            }
        } else {
            if (relativePath.startsWith(File.separator)) {
                fileSystemDocBase += relativePath;
            } else {
                fileSystemDocBase = fileSystemDocBase + File.separatorChar + relativePath;
            }
        }

        return Paths.get(fileSystemDocBase);
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the root directory inside the
     * JAR/WAR/directory where the caller class is located at.
     *
     * @deprecated Use {@link AppRootFinder#findCurrent()} and {@link #of(Path)}.
     */
    @Deprecated
    public static TomcatService forCurrentClassPath() {
        return of(AppRootFinder.findCurrent(1));
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base directory
     * inside the JAR/WAR/directory where the caller class is located at.
     *
     * @deprecated Use {@link AppRootFinder#findCurrent()} and {@link #of(Path, String)}.
     */
    @Deprecated
    public static TomcatService forCurrentClassPath(String docBase) {
        return of(AppRootFinder.findCurrent(1), docBase);
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the root directory inside the
     * JAR/WAR/directory where the specified class is located at.
     *
     * @deprecated Use {@link AppRootFinder#find(Class)} and {@link #of(Path)}.
     */
    @Deprecated
    public static TomcatService forClassPath(Class<?> clazz) {
        return of(AppRootFinder.find(clazz));
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base directory
     * inside the JAR/WAR/directory where the specified class is located at.
     *
     * @deprecated Use {@link AppRootFinder#find(Class)} and {@link #of(Path, String)}.
     */
    @Deprecated
    public static TomcatService forClassPath(Class<?> clazz, String docBase) {
        return of(AppRootFinder.find(clazz), docBase);
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     *
     * @deprecated Use {@link #of(Path)}.
     */
    @Deprecated
    public static TomcatService forFileSystem(Path docBase) {
        return of(docBase);
    }

    /**
     * Creates a new {@link TomcatService} from an existing {@link Tomcat} instance.
     * If the specified {@link Tomcat} instance is not configured properly, the returned {@link TomcatService}
     * may respond with '503 Service Not Available' error.
     *
     * @return a new {@link TomcatService}, which will not manage the provided {@link Tomcat} instance.
     *
     * @deprecated Use {@link #of(Tomcat)}.
     */
    @Deprecated
    public static TomcatService forTomcat(Tomcat tomcat) {
        return of(tomcat);
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     *
     * @return a new {@link TomcatService}, which will not manage the provided {@link Connector} instance.
     *
     * @deprecated Use {@link #of(Connector)}.
     */
    @Deprecated
    public static TomcatService forConnector(Connector connector) {
        return of(connector);
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     *
     * @return a new {@link TomcatService}, which will not manage the provided {@link Connector} instance.
     *
     * @deprecated Use {@link #of(Connector, String)}.
     */
    @Deprecated
    public static TomcatService forConnector(String hostname, Connector connector) {
        return of(connector, hostname);
    }

    static String toString(
            @SuppressWarnings("UnnecessaryFullyQualifiedName") org.apache.catalina.Server server) {

        requireNonNull(server, "server");

        final Service[] services = server.findServices();
        final String serviceName;
        if (services.length == 0) {
            serviceName = "<unknown>";
        } else {
            serviceName = services[0].getName();
        }

        final StringBuilder buf = new StringBuilder(128);

        buf.append("(serviceName: ");
        buf.append(serviceName);
        if (TomcatVersion.major() >= 8) {
            buf.append(", catalinaBase: ");
            buf.append(server.getCatalinaBase());
        }
        buf.append(')');

        return buf.toString();
    }

    TomcatService() {}

    /**
     * Returns Tomcat {@link Connector}.
     */
    public abstract Optional<Connector> connector();

    @Nullable
    abstract String hostName();

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Optional<Adapter> coyoteAdapter = connector().map(c -> c.getProtocolHandler().getAdapter());

        if (!coyoteAdapter.isPresent()) {
            // Tomcat is not configured / stopped.
            throw HttpStatusException.of(HttpStatus.SERVICE_UNAVAILABLE);
        }

        final HttpResponseWriter res = HttpResponse.streaming();
        req.aggregate().handle((aReq, cause) -> {
            try {
                if (cause != null) {
                    logger.warn("{} Failed to aggregate a request:", ctx, cause);
                    if (res.tryWrite(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR))) {
                        res.close();
                    }
                    return null;
                }

                final Request coyoteReq = convertRequest(ctx, aReq);
                if (coyoteReq == null) {
                    if (res.tryWrite(INVALID_AUTHORITY_HEADERS)) {
                        if (res.tryWrite(INVALID_AUTHORITY_DATA)) {
                            res.close();
                        }
                    }
                    return null;
                }
                final Response coyoteRes = new Response();
                coyoteReq.setResponse(coyoteRes);
                coyoteRes.setRequest(coyoteReq);

                final Queue<HttpData> data = new ArrayDeque<>();
                coyoteRes.setOutputBuffer((OutputBuffer) OUTPUT_BUFFER_CONSTRUCTOR.invoke(data));

                ctx.blockingTaskExecutor().execute(() -> {
                    if (!res.isOpen()) {
                        return;
                    }

                    try {
                        coyoteAdapter.get().service(coyoteReq, coyoteRes);
                        final HttpHeaders headers = convertResponse(coyoteRes);
                        if (res.tryWrite(headers)) {
                            for (;;) {
                                final HttpData d = data.poll();
                                if (d == null || !res.tryWrite(d)) {
                                    break;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        logger.warn("{} Failed to produce a response:", ctx, t);
                    } finally {
                        res.close();
                    }
                });
            } catch (Throwable t) {
                logger.warn("{} Failed to invoke Tomcat:", ctx, t);
                res.close();
            }

            return null;
        });

        return res;
    }

    @Nullable
    private Request convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest req) throws Throwable {
        final String mappedPath = ctx.mappedPath();
        final Request coyoteReq = new Request();

        coyoteReq.scheme().setString(req.scheme());

        // Set the remote host/address.
        final InetSocketAddress remoteAddr = ctx.remoteAddress();
        coyoteReq.remoteAddr().setString(remoteAddr.getAddress().getHostAddress());
        coyoteReq.remoteHost().setString(remoteAddr.getHostString());
        coyoteReq.setRemotePort(remoteAddr.getPort());

        // Set the local host/address.
        final InetSocketAddress localAddr = ctx.localAddress();
        coyoteReq.localAddr().setString(localAddr.getAddress().getHostAddress());
        coyoteReq.localName().setString(hostName());
        coyoteReq.setLocalPort(localAddr.getPort());

        final String hostHeader = req.authority();
        final int colonPos = hostHeader.indexOf(':');
        if (colonPos < 0) {
            coyoteReq.serverName().setString(hostHeader);
        } else {
            coyoteReq.serverName().setString(hostHeader.substring(0, colonPos));
            try {
                final int port = Integer.parseInt(hostHeader.substring(colonPos + 1));
                coyoteReq.setServerPort(port);
            } catch (NumberFormatException e) {
                // Invalid port number
                return null;
            }
        }

        // Set the method.
        final HttpMethod method = req.method();
        coyoteReq.method().setString(method.name());

        // Set the request URI.
        final byte[] uriBytes = mappedPath.getBytes(StandardCharsets.US_ASCII);
        coyoteReq.requestURI().setBytes(uriBytes, 0, uriBytes.length);

        // Set the query string if any.
        if (ctx.query() != null) {
            coyoteReq.queryString().setString(ctx.query());
        }

        // Set the headers.
        final MimeHeaders cHeaders = coyoteReq.getMimeHeaders();
        convertHeaders(req.headers(), cHeaders);
        convertHeaders(req.trailers(), cHeaders);

        // Set the content.
        final HttpData content = req.content();
        coyoteReq.setInputBuffer((InputBuffer) INPUT_BUFFER_CONSTRUCTOR.invoke(content));

        return coyoteReq;
    }

    private static void convertHeaders(HttpHeaders headers, MimeHeaders cHeaders) {
        if (headers.isEmpty()) {
            return;
        }

        for (Entry<AsciiString, String> e : headers) {
            final AsciiString k = e.getKey();
            final String v = e.getValue();

            if (k.isEmpty() || k.byteAt(0) == ':') {
                continue;
            }

            final MessageBytes cValue = cHeaders.addValue(k.array(), k.arrayOffset(), k.length());
            final byte[] valueBytes = v.getBytes(StandardCharsets.US_ASCII);
            cValue.setBytes(valueBytes, 0, valueBytes.length);
        }
    }

    private static ResponseHeaders convertResponse(Response coyoteRes) {
        final ResponseHeadersBuilder headers = ResponseHeaders.builder();
        headers.status(coyoteRes.getStatus());

        final String contentType = coyoteRes.getContentType();
        if (contentType != null && !contentType.isEmpty()) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }

        final long contentLength = coyoteRes.getBytesWritten(true); // 'true' will trigger flush.
        final String method = coyoteRes.getRequest().method().toString();
        if (!"HEAD".equals(method)) {
            headers.setLong(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        }

        final MimeHeaders cHeaders = coyoteRes.getMimeHeaders();
        final int numHeaders = cHeaders.size();
        for (int i = 0; i < numHeaders; i++) {
            final AsciiString name = toHeaderName(cHeaders.getName(i));
            if (name == null) {
                continue;
            }

            final String value = toHeaderValue(cHeaders.getValue(i));
            if (value == null) {
                continue;
            }

            headers.add(name.toLowerCase(), value);
        }

        return headers.build();
    }

    @Nullable
    private static AsciiString toHeaderName(MessageBytes value) {
        switch (value.getType()) {
            case MessageBytes.T_BYTES: {
                final ByteChunk chunk = value.getByteChunk();
                return new AsciiString(chunk.getBuffer(), chunk.getOffset(), chunk.getLength(), true);
            }
            case MessageBytes.T_CHARS: {
                final CharChunk chunk = value.getCharChunk();
                return new AsciiString(chunk.getBuffer(), chunk.getOffset(), chunk.getLength());
            }
            case MessageBytes.T_STR: {
                return HttpHeaderNames.of(value.getString());
            }
        }
        return null;
    }

    @Nullable
    private static String toHeaderValue(MessageBytes value) {
        switch (value.getType()) {
            case MessageBytes.T_BYTES: {
                final ByteChunk chunk = value.getByteChunk();
                return new String(chunk.getBuffer(), chunk.getOffset(), chunk.getLength(),
                                  StandardCharsets.US_ASCII);
            }
            case MessageBytes.T_CHARS: {
                final CharChunk chunk = value.getCharChunk();
                return new String(chunk.getBuffer(), chunk.getOffset(), chunk.getLength());
            }
            case MessageBytes.T_STR: {
                return value.getString();
            }
        }
        return null;
    }
}
