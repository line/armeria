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
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.Executor;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
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
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Promise;

final class TomcatServiceInvocationHandler implements ServiceInvocationHandler {

    private static final Logger logger = LoggerFactory.getLogger(TomcatServiceInvocationHandler.class);

    private static final String SERVICE_NAME = "Tomcat-over-Armeria";
    private static final String ENGINE_NAME = SERVICE_NAME;
    private static final String ROOT_CONTEXT_PATH = "";

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
        //

        final StandardEngine engine = new StandardEngine();
        engine.setName(ENGINE_NAME);
        engine.setDefaultHost(config.hostname());
        engine.setRealm(config.realm());

        final StandardService service = new StandardService();
        service.setName(SERVICE_NAME);
        service.setContainer(engine);

        service.addConnector(connector);

        final StandardServer server = new StandardServer();

        final File baseDir = config.baseDir().toFile();
        server.setCatalinaBase(baseDir);
        server.setCatalinaHome(baseDir);

        // See StandardServer.await() for more information about this magic number (-2),
        // which is used for a embedded Tomcat server that manages its life cycle manually.
        server.setPort(-2);

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

    @Override
    public void invoke(ServiceInvocationContext ctx,
                       Executor blockingTaskExecutor, Promise<Object> promise) throws Exception {

        final Request coyoteReq = convertRequest(ctx.originalRequest(), ctx.mappedPath());
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
                promise.trySuccess(convertResponse(coyoteRes, resContent));
            } catch (Throwable t) {
                if (!promise.tryFailure(t)) {
                    ctx.logger().warn("Failed to mark a promise as a failure: {}", promise, t);
                }
            } finally {
                ServiceInvocationContext.removeCurrent();
            }
        });
    }

    private static Request convertRequest(FullHttpRequest req, String mappedPath) {
        final Request coyoteReq = new Request();
        coyoteReq.method().setString(req.method().name());
        final byte[] uriBytes = mappedPath.getBytes(StandardCharsets.US_ASCII);
        coyoteReq.requestURI().setBytes(uriBytes, 0, uriBytes.length);

        final MimeHeaders cHeaders = coyoteReq.getMimeHeaders();
        convertHeaders(req.headers(), cHeaders);
        convertHeaders(req.trailingHeaders(), cHeaders);

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
