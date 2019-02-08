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

import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.apache.catalina.LifecycleState;
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

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
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

    private static final Logger logger = LoggerFactory.getLogger(TomcatService.class);

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

    private static final HttpHeaders INVALID_AUTHORITY_HEADERS =
            HttpHeaders.of(HttpStatus.BAD_REQUEST)
                       .setObject(HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8);
    private static final HttpData INVALID_AUTHORITY_DATA =
            HttpData.ofUtf8(HttpStatus.BAD_REQUEST + "\nInvalid authority");

    TomcatService() {}

    /**
     * Creates a new {@link TomcatService} with the web application at the root directory inside the
     * JAR/WAR/directory where the caller class is located at.
     */
    public static TomcatService forCurrentClassPath() {
        return TomcatServiceBuilder.forCurrentClassPath(3).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base directory
     * inside the JAR/WAR/directory where the caller class is located at.
     */
    public static TomcatService forCurrentClassPath(String docBase) {
        return TomcatServiceBuilder.forCurrentClassPath(docBase, 3).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the root directory inside the
     * JAR/WAR/directory where the specified class is located at.
     */
    public static TomcatService forClassPath(Class<?> clazz) {
        return TomcatServiceBuilder.forClassPath(clazz).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base directory
     * inside the JAR/WAR/directory where the specified class is located at.
     */
    public static TomcatService forClassPath(Class<?> clazz, String docBase) {
        return TomcatServiceBuilder.forClassPath(clazz, docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService forFileSystem(String docBase) {
        return TomcatServiceBuilder.forFileSystem(docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} with the web application at the specified document base, which can
     * be a directory or a JAR/WAR file.
     */
    public static TomcatService forFileSystem(Path docBase) {
        return TomcatServiceBuilder.forFileSystem(docBase).build();
    }

    /**
     * Creates a new {@link TomcatService} from an existing {@link Tomcat} instance.
     * If the specified {@link Tomcat} instance is not configured properly, the returned {@link TomcatService}
     * may respond with '503 Service Not Available' error.
     *
     * @return a new {@link TomcatService}, which will not manage the provided {@link Tomcat} instance.
     */
    public static TomcatService forTomcat(Tomcat tomcat) {
        requireNonNull(tomcat, "tomcat");

        return new UnmanagedTomcatService(tomcat);
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     *
     * @return a new {@link TomcatService}, which will not manage the provided {@link Connector} instance.
     */
    public static TomcatService forConnector(Connector connector) {
        requireNonNull(connector, "connector");
        return new UnmanagedTomcatService(null, connector);
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     *
     * @return a new {@link TomcatService}, which will not manage the provided {@link Connector} instance.
     */
    public static TomcatService forConnector(String hostname, Connector connector) {
        requireNonNull(hostname, "hostname");
        requireNonNull(connector, "connector");

        return new UnmanagedTomcatService(hostname, connector);
    }

    static TomcatService forConfig(TomcatServiceConfig config) {
        final Consumer<Connector> postStopTask = connector -> {
            final org.apache.catalina.Server server = connector.getService().getServer();
            if (server.getState() == LifecycleState.STOPPED) {
                try {
                    logger.info("Destroying an embedded Tomcat: {}", toString(server));
                    server.destroy();
                } catch (Exception e) {
                    logger.warn("Failed to destroy an embedded Tomcat: {}", toString(server), e);
                }
            }
        };

        return new ManagedTomcatService(null, new ManagedConnectorFactory(config), postStopTask);
    }

    static String toString(org.apache.catalina.Server server) {
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
                    res.close(HttpHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
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
    private Request convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage req) throws Throwable {
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

        final String hostHeader = req.headers().authority();
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
        convertHeaders(req.trailingHeaders(), cHeaders);

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

    private static HttpHeaders convertResponse(Response coyoteRes) {
        final HttpHeaders headers = HttpHeaders.of(HttpStatus.valueOf(coyoteRes.getStatus()));

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

        return headers;
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
