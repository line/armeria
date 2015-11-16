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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream.State;
import io.netty.handler.codec.http2.Http2StreamVisitor;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
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
            p.addLast(new SniHandler(sslContexts) {
                @Override
                protected void decode(
                        ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {

                    // FIXME(trustin): Workaround for the problem in ApplicationProtocolNegotiationHadler
                    //                 which refuses to add itself to the pipeline if SslHandler does not
                    //                 exist in the pipeline.

                    super.decode(ctx, in, out);

                    final ChannelPipeline p = ctx.pipeline();
                    if (p.get(SslHandler.class) != null) {
                        configureHttps(p);
                    }
                }
            });
        } else {
            configureHttp(p);
        }
    }

    private void configureHttp(ChannelPipeline p) {
        final HttpServerCodec http1codec = new HttpServerCodec();
        final HttpObjectAggregator http1aggregator = new HttpObjectAggregator(config.maxFrameLength());

        p.addLast(http1codec);
        p.addLast(new HttpServerUpgradeHandler(
                http1codec,
                protocol -> {
                    if (!AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                        return null;
                    }

                    final Http2Connection conn = new DefaultHttp2Connection(true);
                    final Http2FrameListener listener = new InboundHttp2ToHttpAdapter.Builder(conn)
                            .propagateSettings(true).validateHttpHeaders(false)
                            .maxContentLength(config.maxFrameLength()).build();

                    return new Http2ServerUpgradeCodec(
                            new ExtendedHttpToHttp2ConnectionHandler.Builder(p, http1aggregator)
                                    .frameListener(listener).build(conn));
                },
                config.maxFrameLength()));

        p.addLast(http1aggregator);
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

    private static final class ExtendedHttpToHttp2ConnectionHandler extends HttpToHttp2ConnectionHandler {

        static final class Builder extends BuilderBase<HttpToHttp2ConnectionHandler, Builder> {

            private final ChannelPipeline pipeline;
            private final ChannelHandler[] toRemove;

            Builder(ChannelPipeline pipeline, ChannelHandler... toRemove) {
                this.pipeline = pipeline;
                this.toRemove = toRemove;

                // TODO(trustin): Enable max concurrent streams again if the related Netty bug is fixed.
                // https://github.com/netty/netty/commit/455682cae0d7bec0f260c36147702d0fca37f072#commitcomment-13655595
                encoderEnforceMaxConcurrentStreams(false);
            }

            @Override
            protected HttpToHttp2ConnectionHandler build0(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder) {
                return new ExtendedHttpToHttp2ConnectionHandler(
                        pipeline, decoder, encoder, initialSettings(), isValidateHeaders(), toRemove);
            }
        }

        /**
         * XXX(trustin): Don't know why, but {@link Http2ConnectionHandler} does not close the last stream
         *               on a cleartext connection, so we make sure all streams are closed.
         */
        private static final Http2StreamVisitor closeAllStreams = stream -> {
            if (stream.state() != State.CLOSED) {
                stream.close();
            }
            return true;
        };

        private final ChannelPipeline pipeline;
        private final ChannelHandler[] toRemove;

        ExtendedHttpToHttp2ConnectionHandler(
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
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            encoder().connection().forEachActiveStream(closeAllStreams);
            super.close(ctx, promise);
        }
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
            final Http2Connection conn = new DefaultHttp2Connection(true);
            final Http2FrameListener listener = new InboundHttp2ToHttpAdapter.Builder(conn)
                    .propagateSettings(true).validateHttpHeaders(false)
                    .maxContentLength(config.maxFrameLength()).build();

            p.addLast(new ExtendedHttpToHttp2ConnectionHandler.Builder(p).frameListener(listener).build(conn));
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
}
