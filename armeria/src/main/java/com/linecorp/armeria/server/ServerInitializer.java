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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AbstractHttpToHttp2ConnectionHandler;
import com.linecorp.armeria.common.http.Http2GoAwayListener;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.DomainNameMapping;

final class ServerInitializer extends ChannelInitializer<Channel> {

    private final ServerConfig config;
    private final ServerPort port;
    private final DomainNameMapping<SslContext> sslContexts;
    private final Optional<GracefulShutdownHandler> gracefulShutdownHandler;

    ServerInitializer(
            ServerConfig config, ServerPort port,
            DomainNameMapping<SslContext> sslContexts,
            Optional<GracefulShutdownHandler> gracefulShutdownHandler) {

        this.config = requireNonNull(config, "config");
        this.port = requireNonNull(port, "port");
        this.sslContexts = sslContexts;
        this.gracefulShutdownHandler = requireNonNull(gracefulShutdownHandler);
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        final ChannelPipeline p = ch.pipeline();

        if (port.protocol().isTls()) {
            p.addLast(new SniHandler(sslContexts));
            configureHttps(p);
        } else {
            configureHttp(p);
        }
    }

    private void configureHttp(ChannelPipeline p) {
        p.addLast(new Http2PrefaceOrHttpHandler());
        configureRequestCountingHandlers(p);
        p.addLast(new HttpServerHandler(config, SessionProtocol.H1C));
    }

    private void configureRequestCountingHandlers(ChannelPipeline p) {
        if (config.idleTimeoutMillis() > 0) {
            p.addLast(new HttpServerIdleTimeoutHandler(config.idleTimeoutMillis()));
        }
        gracefulShutdownHandler.ifPresent(h -> {
            h.reset();
            p.addLast(h);
        });
    }

    private void configureHttps(ChannelPipeline p) {
        p.addLast(new Http2OrHttpHandler());
    }

    private Http2ConnectionHandler createHttp2ConnectionHandler(ChannelPipeline pipeline, ChannelHandler... toRemove) {
        final boolean validateHeaders = true;
        final Http2Connection conn = new DefaultHttp2Connection(true);
        conn.addListener(new Http2GoAwayListener(pipeline.channel()));

        final Http2FrameListener listener = new InboundHttp2ToHttpAdapterBuilder(conn)
                .propagateSettings(true).validateHttpHeaders(validateHeaders)
                .maxContentLength(config.maxFrameLength()).build();

        Http2FrameReader reader = new DefaultHttp2FrameReader(validateHeaders);
        Http2FrameWriter writer = new DefaultHttp2FrameWriter();

        Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(conn, writer);
        Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(conn, encoder, reader);

        final HttpToHttp2ServerConnectionHandler handler =
                new HttpToHttp2ServerConnectionHandler(pipeline, decoder, encoder, new Http2Settings(),
                                                       validateHeaders, toRemove);

        // Setup post build options
        handler.gracefulShutdownTimeoutMillis(config.idleTimeoutMillis());
        handler.decoder().frameListener(listener);

        return handler;
    }

    private static final class HttpToHttp2ServerConnectionHandler extends AbstractHttpToHttp2ConnectionHandler {

        private final ChannelPipeline pipeline;
        private final ChannelHandler[] toRemove;

        HttpToHttp2ServerConnectionHandler(
                ChannelPipeline pipeline,
                Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                Http2Settings initialSettings, boolean validateHeaders, ChannelHandler... toRemove) {

            super(decoder, encoder, initialSettings, validateHeaders);

            this.pipeline = pipeline;
            this.toRemove = toRemove;
        }

        @Override
        public void onHttpServerUpgrade(Http2Settings settings) throws Http2Exception {
            for (ChannelHandler h: toRemove) {
                pipeline.remove(h);
            }
            super.onHttpServerUpgrade(settings);
        }

        @Override
        protected void onCloseRequest(ChannelHandlerContext ctx) throws Exception {}
    }

    private final class Http2OrHttpHandler extends ApplicationProtocolNegotiationHandler {

        Http2OrHttpHandler() {
            super(ApplicationProtocolNames.HTTP_1_1);
        }

        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                addHttp2Handlers(ctx);
                return;
            }

            if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
                addHttpHandlers(ctx);
                return;
            }

            throw new IllegalStateException("unknown protocol: " + protocol);
        }


        private void addHttp2Handlers(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            p.addLast(createHttp2ConnectionHandler(p));
            configureRequestCountingHandlers(p);
            p.addLast(new HttpServerHandler(config, SessionProtocol.H2));
        }

        private void addHttpHandlers(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            p.addLast(new HttpServerCodec());
            p.addLast(new HttpObjectAggregator(config.maxFrameLength()));
            configureRequestCountingHandlers(p);
            p.addLast(new HttpServerHandler(config, SessionProtocol.H1));
        }
    }

    private final class Http2PrefaceOrHttpHandler extends ByteToMessageDecoder {

        private String name;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            super.handlerAdded(ctx);
            name = ctx.name();
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (in.readableBytes() < 4) {
                return;
            }

            if (in.getInt(in.readerIndex()) == 0x50524920) { // If starts with 'PRI '
                // Probably HTTP/2; received the HTTP/2 preface string.
                configureHttp2(ctx);
            } else {
                // Probably HTTP/1; the client can still upgrade using the traditional HTTP/1 upgrade request.
                configureHttp1WithUpgrade(ctx);
            }

            ctx.pipeline().remove(this);
        }

        private void configureHttp1WithUpgrade(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            final HttpServerCodec http1codec = new HttpServerCodec();
            final HttpObjectAggregator http1aggregator = new HttpObjectAggregator(config.maxFrameLength());

            String baseName = name;
            baseName = addAfter(p, baseName, http1codec);
            baseName = addAfter(p, baseName, new HttpServerUpgradeHandler(
                    http1codec,
                    protocol -> {
                        if (!AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                            return null;
                        }

                        return new Http2ServerUpgradeCodec(
                                createHttp2ConnectionHandler(p, http1aggregator));
                    },
                    config.maxFrameLength()));

            addAfter(p, baseName, http1aggregator);
        }

        private void configureHttp2(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            addAfter(p, name, createHttp2ConnectionHandler(p));
        }

        private String addAfter(ChannelPipeline p, String baseName, ChannelHandler handler) {
            p.addAfter(baseName, null, handler);
            return p.context(handler).name();
        }
    }
}
