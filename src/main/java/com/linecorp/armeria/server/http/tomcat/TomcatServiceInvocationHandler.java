/*
 * Copyright 2015 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.startup.ContextConfig;
import org.apache.coyote.Adapter;
import org.apache.coyote.InputBuffer;
import org.apache.coyote.OutputBuffer;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceInvocationHandler;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Promise;

final class TomcatServiceInvocationHandler implements ServiceInvocationHandler {

    private static final Logger logger = LoggerFactory.getLogger(TomcatServiceInvocationHandler.class);

    private static final String ROOT_CONTEXT_PATH = "";

    /**
     * See {@link StandardServer#await()} for more information about this magic number (-2),
     * which is used for an embedded Tomcat server that manages its life cycle manually.
     */
    private static final int EMBEDDED_TOMCAT_PORT = -2;

    static {
        // Disable JNDI naming provided by Tomcat by default.
        System.setProperty("catalina.useNaming", "false");
    }

    private final TomcatServiceConfig config;
    private final ServerListener configurator = new Configurator();

    private volatile StandardServer server;
    private volatile Adapter coyoteAdapter;

    private Server armeriaServer;

    TomcatServiceInvocationHandler(TomcatServiceConfig config) {
        this.config = requireNonNull(config, "config");
    }

    TomcatServiceConfig config() {
        return config;
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
    }

    void start() {
        logger.info("Starting an embedded Tomcat: {}", config());

        assert server == null;
        assert coyoteAdapter == null;

        // Create the connector with our protocol handler. Tomcat will call ProtocolHandler.setAdapter()
        // on its startup with the Coyote Adapter which gives an access to Tomcat's HTTP service pipeline.
        final Connector connector = new Connector(TomcatProtocolHandler.class.getName());
        connector.setPort(0); // We do not really open a port - just trying to stop the Connector from complaining.
        final ProtocolHandler protocolHandler = connector.getProtocolHandler();

        server = newServer(connector, config());

        // Retrieve the components configured by newServer(), so we can use it in checkConfiguration().
        final Service service = server.findServices()[0];
        @SuppressWarnings("deprecation")
        final Engine engine = (Engine) service.getContainer();
        final StandardHost host = (StandardHost) engine.findChildren()[0];
        final Context context = (Context) host.findChildren()[0];


        // Apply custom configurators set via TomcatServiceBuilder.configurator()
        try {
            config().configurators().forEach(c -> c.accept(server));
        } catch (Throwable t) {
            throw new TomcatServiceException("failed to configure an embedded Tomcat", t);
        }

        // Make sure the configurators did not ruin what we have configured in this method.
        checkConfiguration(service, connector, engine, host, context);

        // Start the server finally.
        try {
            server.start();
        } catch (LifecycleException e) {
            throw new TomcatServiceException("failed to start an embedded Tomcat", e);
        }

        coyoteAdapter = protocolHandler.getAdapter();
    }

    void stop() {
        StandardServer server = this.server;
        this.server = null;
        coyoteAdapter = null;

        if (server != null) {
            logger.info("Stopping an embedded Tomcat: {}", config());
            server.stopAwait();
        }
    }

    private StandardServer newServer(Connector connector, TomcatServiceConfig config) {
        //
        // server <------ services <------ engines <------ realm
        //                                         <------ hosts <------ contexts
        //                         <------ connectors
        //                         <------ executors
        //

        final StandardEngine engine = new StandardEngine();
        engine.setName(config.engineName());
        engine.setDefaultHost(config.hostname());
        engine.setRealm(config.realm());

        final StandardService service = new StandardService();
        service.setName(config.serviceName());
        service.setContainer(engine);

        service.addConnector(connector);

        final StandardServer server = new StandardServer();

        final File baseDir = config.baseDir().toFile();
        server.setCatalinaBase(baseDir);
        server.setCatalinaHome(baseDir);
        server.setPort(EMBEDDED_TOMCAT_PORT);

        server.addService(service);

        // Add the web application context.
        // Get or create a host.
        StandardHost host = (StandardHost) engine.findChild(config.hostname());
        if (host == null) {
            host = new StandardHost();
            host.setName(config.hostname());
            engine.addChild(host);
        }

        // Create a new context and add it to the host.
        final Context ctx;
        try {
            ctx = (Context) Class.forName(host.getContextClass(), true, getClass().getClassLoader()).newInstance();
        } catch (Exception e) {
            throw new TomcatServiceException("failed to create a new context: " + config, e);
        }

        ctx.setPath(ROOT_CONTEXT_PATH);
        ctx.setDocBase(config.docBase().toString());
        ctx.addLifecycleListener(TomcatUtil.getDefaultWebXmlListener());
        ctx.setConfigFile(TomcatUtil.getWebAppConfigFile(ROOT_CONTEXT_PATH, config.docBase()));

        final ContextConfig ctxCfg = new ContextConfig();
        ctxCfg.setDefaultWebXml(TomcatUtil.noDefaultWebXmlPath());
        ctx.addLifecycleListener(ctxCfg);

        host.addChild(ctx);

        return server;
    }

    private void checkConfiguration(Service expectedService, Connector expectedConnector,
                                    Engine expectedEngine, StandardHost expectedHost, Context expectedContext) {


        // Check if Catalina base and home directories have not been changed.
        final File expectedBaseDir = config.baseDir().toFile();
        if (!Objects.equals(server.getCatalinaBase(), expectedBaseDir) ||
            !Objects.equals(server.getCatalinaHome(), expectedBaseDir)) {
            throw new TomcatServiceException("A configurator should never change the Catalina base and home.");
        }

        // Check if the server's port has not been changed.
        if (server.getPort() != EMBEDDED_TOMCAT_PORT) {
            throw new TomcatServiceException("A configurator should never change the port of the server.");
        }

        // Check if the default service has not been removed and a new service has not been added.
        final Service[] services = server.findServices();
        if (services == null || services.length != 1 || services[0] != expectedService) {
            throw new TomcatServiceException(
                    "A configurator should never remove the default service or add a new service.");
        }

        // Check if the name of the default service has not been changed.
        if (!config().serviceName().equals(expectedService.getName())) {
            throw new TomcatServiceException(
                    "A configurator should never change the name of the default service.");
        }

        // Check if the default connector has not been removed
        final Connector[] connectors = expectedService.findConnectors();
        if (connectors == null || Arrays.stream(connectors).noneMatch(c -> c == expectedConnector)) {
            throw new TomcatServiceException("A configurator should never remove the default connector.");
        }

        // Check if the engine has not been changed.
        @SuppressWarnings("deprecation")
        final Container actualEngine = expectedService.getContainer();
        if (actualEngine != expectedEngine) {
            throw new TomcatServiceException(
                    "A configurator should never change the engine of the default service.");
        }

        // Check if the engine's name has not been changed.
        if (!config().engineName().equals(expectedEngine.getName())) {
            throw new TomcatServiceException(
                    "A configurator should never change the name of the default engine.");
        }

        // Check if the default realm has not been changed.
        if (expectedEngine.getRealm() != config().realm()) {
            throw new TomcatServiceException("A configurator should never change the default realm.");
        }

        // Check if the default host has not been removed.
        final Container[] engineChildren = expectedEngine.findChildren();
        if (engineChildren == null || Arrays.stream(engineChildren).noneMatch(c -> c == expectedHost)) {
            throw new TomcatServiceException("A configurator should never remove the default host.");
        }

        // Check if the default context has not been removed.
        final Container[] contextChildren = expectedHost.findChildren();
        if (contextChildren == null || Arrays.stream(contextChildren).noneMatch(c -> c == expectedContext)) {
            throw new TomcatServiceException("A configurator should never remove the default context.");
        }

        // Check if the docBase of the default context has not been changed.
        if (!config.docBase().toString().equals(expectedContext.getDocBase())) {
            throw new TomcatServiceException(
                    "A configurator should never change the docBase of the default context.");
        }
    }

    @Override
    public void invoke(ServiceInvocationContext ctx,
                       Executor blockingTaskExecutor, Promise<Object> promise) throws Exception {

        final Request coyoteReq = convertRequest(ctx);
        final Response coyoteRes = new Response();
        coyoteReq.setResponse(coyoteRes);
        coyoteRes.setRequest(coyoteReq);

        final ByteBuf resContent = ctx.alloc().ioBuffer();
        coyoteRes.setOutputBuffer(new OutputBuffer() {
            private long bytesWritten;

            @Override
            public int doWrite(ByteChunk chunk, Response response) {
                final int length = chunk.getLength();
                resContent.writeBytes(chunk.getBuffer(), chunk.getStart(), length);
                bytesWritten += length;
                return length;
            }

            @Override
            public long getBytesWritten() {
                return bytesWritten;
            }
        });

        blockingTaskExecutor.execute(() -> {
            if (promise.isDone()) {
                return;
            }

            ServiceInvocationContext.setCurrent(ctx);
            try {
                coyoteAdapter.service(coyoteReq, coyoteRes);
                ctx.resolvePromise(promise, convertResponse(coyoteRes, resContent));
            } catch (Throwable t) {
                ctx.rejectPromise(promise, t);
            } finally {
                ServiceInvocationContext.removeCurrent();
            }
        });
    }

    private Request convertRequest(ServiceInvocationContext ctx) {
        final FullHttpRequest req = ctx.originalRequest();
        final String mappedPath = ctx.mappedPath();

        final Request coyoteReq = new Request();

        // Set the remote host/address.
        final InetSocketAddress remoteAddr = (InetSocketAddress) ctx.remoteAddress();
        coyoteReq.remoteAddr().setString(remoteAddr.getAddress().getHostAddress());
        coyoteReq.remoteHost().setString(remoteAddr.getHostString());
        coyoteReq.setRemotePort(remoteAddr.getPort());

        // Set the local host/address.
        final InetSocketAddress localAddr = (InetSocketAddress) ctx.localAddress();
        coyoteReq.localAddr().setString(localAddr.getAddress().getHostAddress());
        coyoteReq.localName().setString(config().hostname());
        coyoteReq.setLocalPort(localAddr.getPort());

        // Set the method.
        final HttpMethod method = req.method();
        coyoteReq.method().setString(method.name());

        // Set the request URI.
        final byte[] uriBytes = mappedPath.getBytes(StandardCharsets.US_ASCII);
        coyoteReq.requestURI().setBytes(uriBytes, 0, uriBytes.length);

        // Set the query string if any.
        final int queryIndex = req.uri().indexOf('?');
        if (queryIndex >= 0) {
            coyoteReq.queryString().setString(req.uri().substring(queryIndex + 1));
        }

        // Set the headers.
        final MimeHeaders cHeaders = coyoteReq.getMimeHeaders();
        convertHeaders(req.headers(), cHeaders);
        convertHeaders(req.trailingHeaders(), cHeaders);

        // Set the content.
        final ByteBuf content = req.content();
        if (content.isReadable()) {
            coyoteReq.setInputBuffer(new InputBuffer() {
                private boolean read;

                @Override
                public int doRead(ByteChunk chunk, Request request) {
                    if (read) {
                        // Read only once.
                        return -1;
                    }

                    read = true;

                    final int readableBytes = content.readableBytes();
                    if (content.hasArray()) {
                        // Note that we do not increase the reference count of the request (and thus its
                        // content as well) in spite that setBytes() does not perform a deep copy,
                        // because it will not be released until the invocation is handled completely.
                        // See HttpServerHandler.handleInvocationResult() for more information.
                        chunk.setBytes(content.array(),
                                       content.arrayOffset() + content.readerIndex(),
                                       readableBytes);
                    } else {
                        final byte[] buf = new byte[readableBytes];
                        content.getBytes(content.readerIndex(), buf);
                        chunk.setBytes(buf, 0, buf.length);
                    }

                    return readableBytes;
                }
            });
        }

        return coyoteReq;
    }

    private static void convertHeaders(HttpHeaders headers, MimeHeaders cHeaders) {
        if (headers.isEmpty()) {
            return;
        }

        for (Iterator<Entry<CharSequence, CharSequence>> i = headers.iteratorCharSequence(); i.hasNext();) {
            final Entry<CharSequence, CharSequence> e = i.next();
            final CharSequence k = e.getKey();
            final CharSequence v = e.getValue();

            final MessageBytes cValue;
            if (k instanceof AsciiString) {
                final AsciiString ak = (AsciiString) k;
                cValue = cHeaders.addValue(ak.array(), ak.arrayOffset(), ak.length());
            } else {
                cValue = cHeaders.addValue(k.toString());
            }

            if (v instanceof AsciiString) {
                final AsciiString av = (AsciiString) v;
                cValue.setBytes(av.array(), av.arrayOffset(), av.length());
            } else {
                final byte[] valueBytes = v.toString().getBytes(StandardCharsets.US_ASCII);
                cValue.setBytes(valueBytes, 0, valueBytes.length);
            }
        }
    }

    private static FullHttpResponse convertResponse(Response coyoteRes, ByteBuf content) {
        final FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(coyoteRes.getStatus()), content);

        final HttpHeaders headers = res.headers();

        final String contentType = coyoteRes.getContentType();
        if (contentType != null && !contentType.isEmpty()) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }

        final MimeHeaders cHeaders = coyoteRes.getMimeHeaders();
        final int numHeaders = cHeaders.size();
        for (int i = 0; i < numHeaders; i++) {
            headers.add(convertMessageBytes(cHeaders.getName(i)),
                        convertMessageBytes(cHeaders.getValue(i)));
        }

        return res;
    }

    private static CharSequence convertMessageBytes(MessageBytes value) {
        if (value.getType() != MessageBytes.T_BYTES) {
            return value.toString();
        }

        final ByteChunk chunk = value.getByteChunk();
        return new AsciiString(chunk.getBuffer(), chunk.getOffset(), chunk.getLength(), false);
    }

    private final class Configurator extends ServerListenerAdapter {
        @Override
        public void serverStarting(Server server) {
            start();
        }

        @Override
        public void serverStopped(Server server) {
            stop();
        }
    }
}
