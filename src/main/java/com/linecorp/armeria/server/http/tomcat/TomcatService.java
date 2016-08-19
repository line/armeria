/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.tomcat;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.catalina.Engine;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.util.URLEncoder;
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

import com.google.common.collect.Sets;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContext.PushHandle;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpResponse;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceUnavailableException;
import com.linecorp.armeria.server.http.HttpService;

import io.netty.util.AsciiString;

/**
 * An {@link HttpService} that dispatches its requests to a web application running in an embedded
 * <a href="http://tomcat.apache.org/">Tomcat</a>.
 *
 * @see TomcatServiceBuilder
 */
public final class TomcatService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TomcatService.class);

    private static final Set<LifecycleState> TOMCAT_START_STATES = Sets.immutableEnumSet(
            LifecycleState.STARTED, LifecycleState.STARTING, LifecycleState.STARTING_PREP);

    private static final int TOMCAT_MAJOR_VERSION;
    private static final URLEncoder TOMCAT_URL_ENCODER;

    static {
        // Detect Tomcat version.
        final Pattern pattern = Pattern.compile("^([1-9][0-9]*)\\.");
        final String version = ServerInfo.getServerNumber();
        final Matcher matcher = pattern.matcher(version);
        int tomcatMajorVersion = -1;
        if (matcher.find()) {
            try {
                tomcatMajorVersion = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // Probably greater than Integer.MAX_VALUE
            }
        }

        TOMCAT_MAJOR_VERSION = tomcatMajorVersion;
        if (TOMCAT_MAJOR_VERSION > 0) {
            logger.info("Tomcat version: {} (major: {})", version, TOMCAT_MAJOR_VERSION);
        } else {
            logger.info("Tomcat version: {} (major: unknown)", version);
        }

        // Initialize the default URLEncoder.
        // NB: We could have used URLEncoder.DEFAULT, but it's not available in pre-8.5.
        TOMCAT_URL_ENCODER = new URLEncoder();
        TOMCAT_URL_ENCODER.addSafeCharacter('~');
        TOMCAT_URL_ENCODER.addSafeCharacter('-');
        TOMCAT_URL_ENCODER.addSafeCharacter('_');
        TOMCAT_URL_ENCODER.addSafeCharacter('.');
        TOMCAT_URL_ENCODER.addSafeCharacter('*');
        TOMCAT_URL_ENCODER.addSafeCharacter('/');
    }

    private static final Set<String> activeEngines = new HashSet<>();

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
     */
    public static TomcatService forTomcat(Tomcat tomcat) {
        requireNonNull(tomcat, "tomcat");

        final String hostname = tomcat.getEngine().getDefaultHost();
        if (hostname == null) {
            throw new IllegalArgumentException("default hostname not configured: " + tomcat);
        }

        final Connector connector = tomcat.getConnector();
        if (connector == null) {
            throw new IllegalArgumentException("connector not configured: " + tomcat);
        }

        return forConnector(hostname, connector);
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     */
    public static TomcatService forConnector(Connector connector) {
        requireNonNull(connector, "connector");
        return new TomcatService(null, hostname -> connector);
    }

    /**
     * Creates a new {@link TomcatService} from an existing Tomcat {@link Connector} instance.
     * If the specified {@link Connector} instance is not configured properly, the returned
     * {@link TomcatService} may respond with '503 Service Not Available' error.
     */
    public static TomcatService forConnector(String hostname, Connector connector) {
        requireNonNull(hostname, "hostname");
        requireNonNull(connector, "connector");

        return new TomcatService(hostname, h -> connector);
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

        return new TomcatService(null, new ManagedConnectorFactory(config), postStopTask);
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
            buf.append(", catalinaBase: " + server.getCatalinaBase());
        }
        buf.append(')');

        return buf.toString();
    }

    private final Function<String, Connector> connectorFactory;
    private final Consumer<Connector> postStopTask;
    private final ServerListener configurator;

    private org.apache.catalina.Server server;
    private Server armeriaServer;
    private String hostname;
    private Connector connector;
    private String engineName;
    private boolean started;

    private TomcatService(String hostname, Function<String, Connector> connectorFactory) {
        this(hostname, connectorFactory, unused -> {});
    }

    private TomcatService(String hostname,
                  Function<String, Connector> connectorFactory, Consumer<Connector> postStopTask) {

        this.hostname = hostname;
        this.connectorFactory = connectorFactory;
        this.postStopTask = postStopTask;
        configurator = new Configurator();
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        if (hostname == null) {
            hostname = cfg.server().defaultHostname();
        }

        if (armeriaServer != null) {
            if (armeriaServer != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        armeriaServer = cfg.server();
        armeriaServer.addListener(configurator);
    }

    /**
     * Returns Tomcat {@link Connector}
     */
    public Connector connector() {
        final Connector connector = this.connector;
        if (connector == null) {
            throw new IllegalStateException("not started yet");
        }

        return connector;
    }

    void start() throws Exception {
        started = false;
        connector = connectorFactory.apply(hostname);
        final Service service = connector.getService();
        if (service == null) {
            return;
        }

        final Engine engine = TomcatUtil.engine(service);
        if (engine == null) {
            return;
        }

        final String engineName = engine.getName();
        if (engineName == null) {
            return;
        }

        if (activeEngines.contains(engineName)) {
            throw new TomcatServiceException("duplicate engine name: " + engineName);
        }

        server = service.getServer();

        if (!TOMCAT_START_STATES.contains(server.getState())) {
            logger.info("Starting an embedded Tomcat: {}", toString(server));
            server.start();
            started = true;
        }

        activeEngines.add(engineName);
        this.engineName = engineName;
    }

    void stop() throws Exception {
        final org.apache.catalina.Server server = this.server;
        final Connector connector = this.connector;
        this.server = null;
        this.connector = null;

        if (engineName != null) {
            activeEngines.remove(engineName);
            engineName = null;
        }

        if (server == null || !started) {
            return;
        }

        try {
            logger.info("Stopping an embedded Tomcat: {}", toString(server));
            server.stop();
        } catch (Exception e) {
            logger.warn("Failed to stop an embedded Tomcat: {}", toString(server), e);
        }

        postStopTask.accept(connector);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final Adapter coyoteAdapter = connector().getProtocolHandler().getAdapter();
        if (coyoteAdapter == null) {
            // Tomcat is not configured / stopped.
            throw ServiceUnavailableException.get();
        }

        final DefaultHttpResponse res = new DefaultHttpResponse();
        req.aggregate().handle(voidFunction((aReq, cause) -> {
            if (cause != null) {
                logger.warn("{} Failed to aggregate a request:", ctx, cause);
                res.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                return;
            }

            try {
                final Request coyoteReq = convertRequest(ctx, aReq);
                if (coyoteReq == null) {
                    res.respond(HttpStatus.BAD_REQUEST);
                    return;
                }
                final Response coyoteRes = new Response();
                coyoteReq.setResponse(coyoteRes);
                coyoteRes.setRequest(coyoteReq);

                final Queue<HttpData> data = new ArrayDeque<>();
                coyoteRes.setOutputBuffer(new OutputBufferImpl(data));

                ctx.blockingTaskExecutor().execute(() -> {
                    if (!res.isOpen()) {
                        return;
                    }

                    try (PushHandle ignored = RequestContext.push(ctx)) {
                        coyoteAdapter.service(coyoteReq, coyoteRes);
                        final HttpHeaders headers = convertResponse(coyoteRes);
                        res.write(headers);
                        for (;;) {
                            final HttpData d = data.poll();
                            if (d == null) {
                                break;
                            }

                            res.write(d);
                        }
                        res.close();
                    } catch (Throwable t) {
                        logger.warn("{} Failed to produce a response:", ctx, t);
                        res.close();
                    }
                });
            } catch (Throwable t) {
                logger.warn("{} Failed to invoke Tomcat:", ctx, t);
                res.close();
            }
        })).exceptionally(CompletionActions::log);

        return res;
    }

    @Nullable
    private Request convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage req) {
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
        coyoteReq.localName().setString(hostname);
        coyoteReq.setLocalPort(localAddr.getPort());

        final String hostHeader = req.headers().authority();
        int colonPos = hostHeader.indexOf(':');
        if (colonPos < 0) {
            coyoteReq.serverName().setString(hostHeader);
        } else {
            coyoteReq.serverName().setString(hostHeader.substring(0, colonPos));
            try {
                int port = Integer.parseInt(hostHeader.substring(colonPos + 1));
                coyoteReq.setServerPort(port);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // Set the method.
        final HttpMethod method = req.method();
        coyoteReq.method().setString(method.name());

        // Set the request URI.
        final byte[] uriBytes = TOMCAT_URL_ENCODER.encode(mappedPath)
                                                  .getBytes(StandardCharsets.US_ASCII);
        coyoteReq.requestURI().setBytes(uriBytes, 0, uriBytes.length);

        // Set the query string if any.
        final int queryIndex = req.path().indexOf('?');
        if (queryIndex >= 0) {
            coyoteReq.queryString().setString(req.path().substring(queryIndex + 1));
        }

        // Set the headers.
        final MimeHeaders cHeaders = coyoteReq.getMimeHeaders();
        convertHeaders(req.headers(), cHeaders);
        convertHeaders(req.trailingHeaders(), cHeaders);

        // Set the content.
        final HttpData content = req.content();
        if (!content.isEmpty()) {
            coyoteReq.setInputBuffer(new InputBufferImpl(content));
        }

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
                return new AsciiString(value.getString());
            }
        }
        return null;
    }

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

    private final class Configurator extends ServerListenerAdapter {
        @Override
        public void serverStarting(Server server) throws Exception {
            start();
        }

        @Override
        public void serverStopped(Server server) throws Exception {
            stop();
        }
    }

    private static class InputBufferImpl implements InputBuffer {
        private final HttpData content;
        private boolean read;

        InputBufferImpl(HttpData content) {
            this.content = content;
        }

        @Override
        public int doRead(ByteChunk chunk) {
            if (read) {
                // Read only once.
                return -1;
            }

            read = true;

            final int readableBytes = content.length();
            chunk.setBytes(content.array(), content.offset(), readableBytes);

            return readableBytes;
        }

        // NB: Do not remove; required for Tomcat 8 or older.
        @SuppressWarnings("unused")
        public int doRead(ByteChunk chunk, Request request) {
            return doRead(chunk);
        }
    }

    private static class OutputBufferImpl implements OutputBuffer {
        private final Queue<HttpData> data;
        private long bytesWritten;

        OutputBufferImpl(Queue<HttpData> data) {
            this.data = data;
        }

        @Override
        public int doWrite(ByteChunk chunk) {
            final int start = chunk.getStart();
            final int end = chunk.getEnd();
            final int length = end - start;
            if (length == 0) {
                return 0;
            }

            // NB: We make a copy because Tomcat reuses the underlying byte array of 'chunk'.
            final byte[] content = Arrays.copyOfRange(chunk.getBuffer(), start, end);

            data.add(HttpData.of(content));

            bytesWritten += length;
            return length;
        }

        // NB: Do not remove; required for Tomcat 8 or older.
        @SuppressWarnings("unused")
        public int doWrite(ByteChunk chunk, Response response) {
            return doWrite(chunk);
        }

        @Override
        public long getBytesWritten() {
            return bytesWritten;
        }
    }
}
