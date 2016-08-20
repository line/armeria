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

package com.linecorp.armeria.server.http;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.FlushConsolidationHandler;
import com.linecorp.armeria.internal.ReadSuppressingHandler;
import com.linecorp.armeria.internal.http.Http2GoAwayListener;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.ServerPort;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
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
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.DomainNameMapping;

/**
 * Configures Netty {@link ChannelPipeline} to serve HTTP/1 and 2 requests.
 */
public final class HttpServerPipelineConfigurator extends ChannelInitializer<Channel> {

    private static final AsciiString SCHEME_HTTP = AsciiString.of("http");
    private static final AsciiString SCHEME_HTTPS = AsciiString.of("https");

    private static final int UPGRADE_REQUEST_MAX_LENGTH = 16384;

    private final ServerConfig config;
    private final ServerPort port;
    private final DomainNameMapping<SslContext> sslContexts;
    private final Optional<? extends ChannelHandler> gracefulShutdownHandler;

    /**
     * Creates a new instance.
     */
    public HttpServerPipelineConfigurator(
            ServerConfig config, ServerPort port,
            DomainNameMapping<SslContext> sslContexts,
            Optional<? extends ChannelHandler> gracefulShutdownHandler) {

        this.config = requireNonNull(config, "config");
        this.port = requireNonNull(port, "port");
        this.sslContexts = sslContexts;
        this.gracefulShutdownHandler = requireNonNull(gracefulShutdownHandler);
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        final ChannelPipeline p = ch.pipeline();
        p.addLast(new FlushConsolidationHandler());
        p.addLast(ReadSuppressingHandler.INSTANCE);

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
            p.addFirst(new HttpServerIdleTimeoutHandler(config.idleTimeoutMillis()));
        }
        gracefulShutdownHandler.ifPresent(p::addLast);
    }

    private void configureHttps(ChannelPipeline p) {
        p.addLast(new Http2OrHttpHandler());
    }

    private Http2ConnectionHandler newHttp2ConnectionHandler(ChannelPipeline pipeline) {

        final Http2Connection conn = new DefaultHttp2Connection(true);
        conn.addListener(new Http2GoAwayListener(pipeline.channel()));

        Http2FrameReader reader = new DefaultHttp2FrameReader(true);
        Http2FrameWriter writer = new DefaultHttp2FrameWriter();

        Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(conn, writer);
        Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(conn, encoder, reader);

        final Http2ConnectionHandler handler =
                new Http2ServerConnectionHandler(decoder, encoder, new Http2Settings());

        // Setup post build options
        final Http2RequestDecoder listener =
                new Http2RequestDecoder(config, pipeline.channel(), handler.encoder());

        handler.connection().addListener(listener);
        handler.decoder().frameListener(listener);
        handler.gracefulShutdownTimeoutMillis(config.idleTimeoutMillis());

        return handler;
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
            p.addLast(newHttp2ConnectionHandler(p));
            configureRequestCountingHandlers(p);
            p.addLast(new HttpServerHandler(config, SessionProtocol.H2));
        }

        private void addHttpHandlers(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            p.addLast(new HttpServerCodec());
            p.addLast(new Http1RequestDecoder(config, ctx.channel(), SCHEME_HTTPS));
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

            String baseName = name;
            baseName = addAfter(p, baseName, http1codec);
            baseName = addAfter(p, baseName, new HttpServerUpgradeHandler(
                    http1codec,
                    protocol -> {
                        if (!AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                            return null;
                        }

                        return new Http2ServerUpgradeCodec(
                                newHttp2ConnectionHandler(p));
                    },
                    UPGRADE_REQUEST_MAX_LENGTH));

            addAfter(p, baseName, new Http1RequestDecoder(config, ctx.channel(), SCHEME_HTTP));
        }

        private void configureHttp2(ChannelHandlerContext ctx) {
            final ChannelPipeline p = ctx.pipeline();
            addAfter(p, name, newHttp2ConnectionHandler(p));
        }

        private String addAfter(ChannelPipeline p, String baseName, ChannelHandler handler) {
            p.addAfter(baseName, null, handler);
            return p.context(handler).name();
        }
    }
}
