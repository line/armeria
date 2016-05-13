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

package com.linecorp.armeria.server.http.jetty;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
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

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceInvocationHandler;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.concurrent.Promise;

final class JettyServiceInvocationHandler implements ServiceInvocationHandler {

    private static final Logger logger = LoggerFactory.getLogger(JettyServiceInvocationHandler.class);

    private final Function<ExecutorService, Server> serverFactory;
    private final Consumer<Server> postStopTask;
    private final Configurator configurator;

    private String hostname;
    private Server server;
    private ArmeriaConnector connector;
    private com.linecorp.armeria.server.Server armeriaServer;
    private boolean startedServer;

    JettyServiceInvocationHandler(String hostname,
                                  Function<ExecutorService, Server> serverFactory,
                                  Consumer<Server> postStopTask) {
        this.hostname = hostname;
        this.serverFactory = serverFactory;
        this.postStopTask = postStopTask;
        configurator = new Configurator();
    }

    @Override
    public void handlerAdded(ServiceConfig cfg) throws Exception {
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
    public void invoke(ServiceInvocationContext ctx, Executor blockingTaskExecutor,
                       Promise<Object> promise) throws Exception {

        final ArmeriaConnector connector = this.connector;
        final FullHttpRequest aReq = ctx.originalRequest();
        final ByteBuf out = ctx.alloc().ioBuffer();

        boolean submitted = false;
        try {
            final ArmeriaHttpTransport transport = new ArmeriaHttpTransport(out);
            final HttpChannel httpChannel = new HttpChannel(
                    connector,
                    connector.getHttpConfiguration(),
                    new ArmeriaEndPoint(hostname, connector.getScheduler(),
                                        ctx.localAddress(), ctx.remoteAddress()),
                    transport);

            fillRequest(ctx, aReq, httpChannel.getRequest());

            blockingTaskExecutor.execute(() -> invoke(ctx, promise, transport, httpChannel));
            submitted = true;
        } catch (Throwable t) {
            ctx.rejectPromise(promise, t);
        } finally {
            if (!submitted) {
                out.release();
            }
        }
    }

    private void invoke(ServiceInvocationContext ctx, Promise<Object> promise,
                        ArmeriaHttpTransport transport, HttpChannel httpChannel) {

        final ByteBuf out = transport.out;
        boolean success = false;
        try {
            server.handle(httpChannel);
            httpChannel.getResponse().getHttpOutput().flush();

            final Throwable cause = transport.cause;
            if (cause == null) {
                ctx.resolvePromise(promise, toFullHttpResponse(transport, out));
                success = true;
            } else {
                ctx.rejectPromise(promise, cause);
            }
        } catch (Throwable t) {
            ctx.rejectPromise(promise, t);
        } finally {
            if (!success) {
                out.release();
            }
        }
    }

    private static void fillRequest(
            ServiceInvocationContext ctx, FullHttpRequest aReq, Request jReq) {

        jReq.setDispatcherType(DispatcherType.REQUEST);
        jReq.setAsyncSupported(true, "armeria");
        jReq.setSecure(ctx.scheme().sessionProtocol().isTls());
        jReq.setMetaData(toRequestMetadata(ctx, aReq));

        if (aReq.content().isReadable()) {
            jReq.getHttpInput().addContent(new Content(aReq.content().nioBuffer()));
        }
        jReq.getHttpInput().eof();
    }

    private static MetaData.Request toRequestMetadata(ServiceInvocationContext ctx, FullHttpRequest aReq) {
        // Construct the HttpURI
        final StringBuilder uriBuf = new StringBuilder();
        uriBuf.append(ctx.scheme().sessionProtocol().isTls() ? "https" : "http");
        uriBuf.append("://");
        uriBuf.append(ctx.host());
        uriBuf.append(':');
        uriBuf.append(((InetSocketAddress) ctx.localAddress()).getPort());
        uriBuf.append(aReq.uri());

        final HttpURI uri = new HttpURI(uriBuf.toString());
        uri.setPath(ctx.mappedPath());

        // Convert HttpHeaders to HttpFields
        final HttpFields headers = new HttpFields(aReq.headers().size());
        aReq.headers().forEach(e -> headers.add(e.getKey(), e.getValue()));

        return new MetaData.Request(
                ctx.method(), uri, HttpVersion.HTTP_1_1, headers, aReq.content().readableBytes());
    }

    private static FullHttpResponse toFullHttpResponse(ArmeriaHttpTransport transport, ByteBuf content) {
        final MetaData.Response info = transport.info;
        if (info == null) {
            throw new IllegalStateException("response metadata unavailable");
        }

        final FullHttpResponse res = new DefaultFullHttpResponse(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(info.getStatus()),
                content, false);

        info.getFields().forEach(e -> res.headers().add(e.getName(), e.getValue()));

        return res;
    }

    private static final class ArmeriaHttpTransport implements HttpTransport {

        final ByteBuf out;
        MetaData.Response info;
        Throwable cause;

        ArmeriaHttpTransport(ByteBuf out) {
            this.out = out;
        }

        @Override
        public void send(MetaData.Response info, boolean head,
                         ByteBuffer content, boolean lastContent, Callback callback) {

            if (info != null) {
                this.info = info;
            }

            out.writeBytes(content);
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
        ArmeriaEndPoint(String hostname, Scheduler scheduler, SocketAddress local, SocketAddress remote) {
            super(scheduler, addHostname((InetSocketAddress) local, hostname), (InetSocketAddress) remote);

            setIdleTimeout(getIdleTimeout());
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
        public void shutdownOutput() {}

        @Override
        public boolean isOutputShutdown() {
            return false;
        }

        @Override
        public boolean isInputShutdown() {
            return false;
        }

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
        public boolean isOpen() {
            return true;
        }
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
