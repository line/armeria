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

package com.linecorp.armeria.server.jetty;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpInput.Content;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;

/**
 * An {@link HttpService} that dispatches its requests to a web application running in an embedded
 * <a href="https://www.eclipse.org/jetty/">Jetty</a>.
 *
 * @see JettyServiceBuilder
 */
public final class JettyService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(JettyService.class);

    private static final Splitter PATH_SPLITTER = Splitter.on('/');

    /**
     * Creates a new {@link JettyService} from an existing Jetty {@link Server}.
     *
     * @param jettyServer the Jetty {@link Server}
     */
    public static JettyService forServer(Server jettyServer) {
        requireNonNull(jettyServer, "jettyServer");
        return new JettyService(null, blockingTaskExecutor -> jettyServer);
    }

    /**
     * Creates a new {@link JettyService} from an existing Jetty {@link Server}.
     *
     * @param hostname the default hostname
     * @param jettyServer the Jetty {@link Server}
     */
    public static JettyService forServer(String hostname, Server jettyServer) {
        requireNonNull(hostname, "hostname");
        requireNonNull(jettyServer, "jettyServer");
        return new JettyService(hostname, blockingTaskExecutor -> jettyServer);
    }

    static JettyService forConfig(JettyServiceConfig config) {
        final Function<ExecutorService, Server> serverFactory = blockingTaskExecutor -> {
            final Server server = new Server(new ArmeriaThreadPool(blockingTaskExecutor));

            config.dumpAfterStart().ifPresent(server::setDumpAfterStart);
            config.dumpBeforeStop().ifPresent(server::setDumpBeforeStop);
            config.stopTimeoutMillis().ifPresent(server::setStopTimeout);

            config.handler().ifPresent(server::setHandler);
            config.requestLog().ifPresent(server::setRequestLog);
            config.sessionIdManagerFactory().ifPresent(
                    factory -> server.setSessionIdManager(factory.apply(server)));

            config.handlerWrappers().forEach(server::insertHandler);
            config.attrs().forEach(server::setAttribute);
            config.beans().forEach(bean -> {
                final Boolean managed = bean.isManaged();
                if (managed == null) {
                    server.addBean(bean.bean());
                } else {
                    server.addBean(bean.bean(), managed);
                }
            });

            config.eventListeners().forEach(server::addEventListener);
            config.lifeCycleListeners().forEach(server::addLifeCycleListener);

            config.configurators().forEach(c -> c.accept(server));

            return server;
        };

        final Consumer<Server> postStopTask = server -> {
            try {
                logger.info("Destroying an embedded Jetty: {}", server);
                server.destroy();
            } catch (Exception e) {
                logger.warn("Failed to destroy an embedded Jetty: {}", server, e);
            }
        };

        return new JettyService(config.hostname().orElse(null), serverFactory, postStopTask);
    }

    private final Function<ExecutorService, Server> serverFactory;
    private final Consumer<Server> postStopTask;
    private final Configurator configurator;

    private String hostname;
    private Server server;
    private ArmeriaConnector connector;
    private com.linecorp.armeria.server.Server armeriaServer;
    private boolean startedServer;

    private JettyService(String hostname, Function<ExecutorService, Server> serverSupplier) {
        this(hostname, serverSupplier, unused -> { /* unused */ });
    }

    private JettyService(String hostname,
                         Function<ExecutorService, Server> serverFactory,
                         Consumer<Server> postStopTask) {

        this.hostname = hostname;
        this.serverFactory = serverFactory;
        this.postStopTask = postStopTask;
        configurator = new Configurator();
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        if (armeriaServer != null) {
            if (armeriaServer != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        armeriaServer = cfg.server();
        armeriaServer.addListener(configurator);
        if (hostname == null) {
            hostname = armeriaServer.defaultHostname();
        }
    }

    void start() throws Exception {
        boolean success = false;
        try {
            server = serverFactory.apply(armeriaServer.config().blockingTaskExecutor());
            connector = new ArmeriaConnector(server);
            server.addConnector(connector);

            if (!server.isStarted()) {
                logger.info("Starting an embedded Jetty: {}", server);
                server.start();
                startedServer = true;
            } else {
                startedServer = false;
            }
            success = true;
        } finally {
            if (!success) {
                server = null;
                connector = null;
            }
        }
    }

    void stop() throws Exception {
        final Server server = this.server;
        this.server = null;
        connector = null;

        if (server == null || !startedServer) {
            return;
        }

        try {
            logger.info("Stopping an embedded Jetty: {}", server);
            server.stop();
        } catch (Exception e) {
            logger.warn("Failed to stop an embedded Jetty: {}", server, e);
        }

        postStopTask.accept(server);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final ArmeriaConnector connector = this.connector;

        final DefaultHttpResponse res = new DefaultHttpResponse();

        req.aggregate().handle(voidFunction((aReq, cause) -> {
            if (cause != null) {
                logger.warn("{} Failed to aggregate a request:", ctx, cause);
                res.respond(HttpStatus.INTERNAL_SERVER_ERROR);
                return;
            }

            boolean success = false;
            try {
                final ArmeriaHttpTransport transport = new ArmeriaHttpTransport(req.method());
                final HttpChannel httpChannel = new HttpChannel(
                        connector,
                        connector.getHttpConfiguration(),
                        new ArmeriaEndPoint(hostname, connector.getScheduler(),
                                            ctx.localAddress(), ctx.remoteAddress()),
                        transport);

                fillRequest(ctx, aReq, httpChannel.getRequest());

                ctx.blockingTaskExecutor().execute(() -> invoke(ctx, res, transport, httpChannel));
                success = true;
            } finally {
                if (!success) {
                    res.close();
                }
            }
        })).exceptionally(CompletionActions::log);

        return res;
    }

    private void invoke(ServiceRequestContext ctx, HttpResponseWriter res,
                        ArmeriaHttpTransport transport, HttpChannel httpChannel) {

        final Queue<HttpData> out = transport.out;
        try {
            server.handle(httpChannel);
            httpChannel.getResponse().getHttpOutput().flush();

            final Throwable cause = transport.cause;
            if (cause != null) {
                throw cause;
            }

            final HttpHeaders headers = toResponseHeaders(transport);
            res.write(headers);
            for (;;) {
                final HttpData data = out.poll();
                if (data == null || !res.write(data)) {
                    break;
                }
            }
            res.close();
        } catch (Throwable t) {
            logger.warn("{} Failed to produce a response:", ctx, t);
            res.close();
        }
    }

    private static void fillRequest(
            ServiceRequestContext ctx, AggregatedHttpMessage aReq, Request jReq) {

        jReq.setDispatcherType(DispatcherType.REQUEST);
        jReq.setAsyncSupported(false, "armeria");
        jReq.setSecure(ctx.sessionProtocol().isTls());
        jReq.setMetaData(toRequestMetadata(ctx, aReq));

        final HttpData content = aReq.content();
        if (!content.isEmpty()) {
            jReq.getHttpInput().addContent(new Content(ByteBuffer.wrap(
                    content.array(), content.offset(), content.length())));
        }
        jReq.getHttpInput().eof();
    }

    private static MetaData.Request toRequestMetadata(ServiceRequestContext ctx, AggregatedHttpMessage aReq) {
        // Construct the HttpURI
        final StringBuilder uriBuf = new StringBuilder();
        final HttpHeaders aHeaders = aReq.headers();

        uriBuf.append(ctx.sessionProtocol().isTls() ? "https" : "http");
        uriBuf.append("://");
        uriBuf.append(aHeaders.authority());
        uriBuf.append(aHeaders.path());

        final HttpURI uri = new HttpURI(uriBuf.toString());
        uri.setPath(ctx.mappedPath());

        // Convert HttpHeaders to HttpFields
        final HttpFields jHeaders = new HttpFields(aHeaders.size());
        aHeaders.forEach(e -> {
            final AsciiString key = e.getKey();
            if (!key.isEmpty() && key.byteAt(0) != ':') {
                jHeaders.add(key.toString(), e.getValue());
            }
        });

        return new MetaData.Request(
                aHeaders.method().name(), uri, HttpVersion.HTTP_1_1, jHeaders, aReq.content().length());
    }

    private static HttpHeaders toResponseHeaders(ArmeriaHttpTransport transport) {
        final MetaData.Response info = transport.info;
        if (info == null) {
            throw new IllegalStateException("response metadata unavailable");
        }

        final HttpHeaders headers = HttpHeaders.of(HttpStatus.valueOf(info.getStatus()));
        info.getFields().forEach(e -> headers.add(HttpHeaderNames.of(e.getName()), e.getValue()));

        if (transport.method != HttpMethod.HEAD) {
            headers.setLong(HttpHeaderNames.CONTENT_LENGTH, transport.contentLength);
        }

        return headers;
    }

    private static final class ArmeriaHttpTransport implements HttpTransport {

        final HttpMethod method;
        final Queue<HttpData> out = new ArrayDeque<>();
        long contentLength;
        MetaData.Response info;
        Throwable cause;

        ArmeriaHttpTransport(HttpMethod method) {
            this.method = method;
        }

        @Override
        public void send(MetaData.Response info, boolean head,
                         ByteBuffer content, boolean lastContent, Callback callback) {

            if (info != null) {
                this.info = info;
            }

            final int length = content.remaining();
            if (length == 0) {
                callback.succeeded();
                return;
            }

            if (content.hasArray()) {
                final int from = content.arrayOffset() + content.position();
                out.add(HttpData.of(Arrays.copyOfRange(content.array(), from, from + length)));
                content.position(content.position() + length);
            } else {
                final byte[] data = new byte[length];
                content.get(data);
                out.add(HttpData.of(data));
            }

            contentLength += length;
            callback.succeeded();
        }

        @Override
        public boolean isPushSupported() {
            return false;
        }

        @Override
        public void push(MetaData.Request request) {}

        @Override
        public void onCompleted() {}

        @Override
        public void abort(Throwable failure) {
            cause = failure;
        }

        @Override
        public boolean isOptimizedForDirectBuffers() {
            return false;
        }
    }

    private static final class ArmeriaEndPoint extends AbstractEndPoint {

        private final InetSocketAddress localAddress;
        private final InetSocketAddress remoteAddress;

        ArmeriaEndPoint(String hostname, Scheduler scheduler, SocketAddress local, SocketAddress remote) {
            super(scheduler);

            localAddress = addHostname((InetSocketAddress) local, hostname);
            remoteAddress = (InetSocketAddress) remote;

            setIdleTimeout(getIdleTimeout());
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return localAddress;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        /**
         * Adds the hostname string to the specified {@link InetSocketAddress} so that
         * Jetty's {@code ServletRequest.getLocalName()} implementation returns the configured hostname.
         */
        private static InetSocketAddress addHostname(InetSocketAddress address, String hostname) {
            try {
                return new InetSocketAddress(InetAddress.getByAddress(
                        hostname, address.getAddress().getAddress()), address.getPort());
            } catch (UnknownHostException e) {
                throw new Error(e); // Should never happen
            }
        }

        @Override
        protected void onIncompleteFlush() {}

        @Override
        protected void needsFillInterest() {}

        @Override
        public int fill(ByteBuffer buffer) {
            return 0;
        }

        @Override
        public boolean flush(ByteBuffer... buffer) {
            return true;
        }

        @Override
        public Object getTransport() {
            return null;
        }

        @Override
        protected void doShutdownInput() {}

        @Override
        protected void doShutdownOutput() {}

        @Override
        protected void doClose() {}
    }

    private final class Configurator extends ServerListenerAdapter {
        @Override
        public void serverStarting(com.linecorp.armeria.server.Server server) throws Exception {
            start();
        }

        @Override
        public void serverStopped(com.linecorp.armeria.server.Server server) throws Exception {
            stop();
        }
    }
}
