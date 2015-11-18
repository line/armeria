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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.Set;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream.State;
import io.netty.handler.codec.http2.Http2StreamVisitor;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;

class HttpConfigurator extends ChannelInitializer<Channel> {

    private static final Logger logger = LoggerFactory.getLogger(HttpConfigurator.class);

    private static final Set<SessionProtocol> http2preferredProtocols = EnumSet.of(SessionProtocol.H2,
                                                                                  SessionProtocol.H2C,
                                                                                  SessionProtocol.HTTP,
                                                                                  SessionProtocol.HTTPS);
    private final SslContext sslCtx;
    private final boolean isHttp2Preferred;
    private final RemoteInvokerOptions options;
    private final SessionListener sessionListener;

    HttpConfigurator(SessionProtocol sessionProtocol,
                     RemoteInvokerOptions options, SessionListener sessionListener) {
        isHttp2Preferred = http2preferredProtocols.contains(sessionProtocol);
        this.options = requireNonNull(options, "options");
        this.sessionListener = requireNonNull(sessionListener, "sessionListener");

        if (sessionProtocol.isTls()) {
            try {
                SslContextBuilder builder = SslContextBuilder.forClient();
                options.trustManagerFactory().ifPresent(builder::trustManager);

                if (isHttp2Preferred) {
                    builder.ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                           .applicationProtocolConfig(new ApplicationProtocolConfig(
                                   ApplicationProtocolConfig.Protocol.ALPN,
                                   // NO_ADVERTISE is currently the only mode supported by both OpenSsl and
                                   // JDK providers.
                                   ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                                   // ACCEPT is currently the only mode supported by both OpenSsl and JDK
                                   // providers.
                                   ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                                   ApplicationProtocolNames.HTTP_2));
                }
                sslCtx = builder.build();
            } catch (SSLException e) {
                throw new IllegalStateException("failed to create a SslContext", e);
            }
        } else {
            sslCtx = null;
        }
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        if (sslCtx != null) {
            configureAsHttps(ch);
        } else {
            configureAsHttp(ch);
        }
    }

    // refer https://http2.github.io/http2-spec/#discover-https
    private void configureAsHttps(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        SslHandler sslHandler = sslCtx.newHandler(ch.alloc());
        pipeline.addLast(sslHandler);
        pipeline.addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof SslHandshakeCompletionEvent) {
                    SslHandshakeCompletionEvent event = (SslHandshakeCompletionEvent) evt;
                    ChannelPipeline p = ctx.pipeline();
                    final SessionProtocol protocol;
                    if (event.isSuccess()) {
                        if (isHttp2Protocol(sslHandler)) {
                            p.addLast(createHttp2ConnectionHandler());
                            protocol = SessionProtocol.H2;
                        } else {
                            installHttpHandlers(p);
                            protocol = SessionProtocol.H1;
                        }
                        p.addLast(new HttpClientIdleTimeoutHandler(options.idleTimeoutMillis()));
                        markHttpConnectionFinished(ctx, protocol);
                    }
                    p.remove(this);
                }
                ctx.fireUserEventTriggered(evt);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                logger.warn("{} Unexpected exception:", ctx.channel(), cause);
                ctx.close();
            }
        });
    }

    private void installHttpHandlers(ChannelPipeline p) {
        p.addLast(new HttpClientCodec());
        p.addLast(new HttpObjectAggregator(options.maxFrameLength()));
        p.addLast(new ChannelOutboundHandlerAdapter() {
            @Override
            public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
                sessionListener.sessionDeactivated(ctx);
                ctx.close();
            }
        });
    }

    private void markHttpConnectionFinished(ChannelHandlerContext ctx, SessionProtocol protocol) {
        sessionListener.sessionActivated(ctx, protocol);
    }

    // refer https://http2.github.io/http2-spec/#discover-http
    private void configureAsHttp(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        if (isHttp2Preferred) {
            HttpClientCodec http1Codec = new HttpClientCodec();
            Http2ClientUpgradeCodec http2ClientUpgradeCodec =
                    new Http2ClientUpgradeCodec(createHttp2ConnectionHandler());
            HttpClientUpgradeHandler http2UpgradeHandler =
                    new HttpClientUpgradeHandler(http1Codec, http2ClientUpgradeCodec, options.maxFrameLength());

            pipeline.addLast(http1Codec);
            pipeline.addLast(http2UpgradeHandler);
            pipeline.addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                    if (evt instanceof HttpClientUpgradeHandler.UpgradeEvent) {
                        SessionProtocol protocol = SessionProtocol.H1C;
                        switch ((HttpClientUpgradeHandler.UpgradeEvent) evt) {
                        case UPGRADE_SUCCESSFUL:
                            protocol = SessionProtocol.H2C;
                        case UPGRADE_REJECTED:
                            markHttpConnectionFinished(ctx, protocol);
                            pipeline.remove(this);
                            break;
                        }
                    }
                    ctx.fireUserEventTriggered(evt);
                }
            });
            pipeline.addLast(new UpgradeRequestHandler());
        } else {
            installHttpHandlers(pipeline);
            markHttpConnectionFinished(pipeline.firstContext(), SessionProtocol.H1C);
        }

        long idleTimeoutMillis = options.idleTimeoutMillis();
        if (idleTimeoutMillis > 0) {
            pipeline.addLast(new HttpClientIdleTimeoutHandler(idleTimeoutMillis));
        }

    }

    protected boolean isHttp2Protocol(SslHandler sslHandler) {
        return ApplicationProtocolNames.HTTP_2.equals(sslHandler.applicationProtocol());
    }

    /**
     * A handler that triggers the cleartext upgrade to HTTP/2 by sending an initial HTTP request.
     */
    private static final class UpgradeRequestHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
            ctx.fireChannelActive();

            // Done with this handler, remove it from the pipeline.
            ctx.pipeline().remove(this);
        }
    }

    private Http2ConnectionHandler createHttp2ConnectionHandler() {
        final Http2Connection conn = new DefaultHttp2Connection(false);
        final InboundHttp2ToHttpAdapter listener = new ExtendedInboundHttp2ToHttpAdapter.Builder(conn)
                .propagateSettings(true).validateHttpHeaders(false)
                .maxContentLength(options.maxFrameLength()).build();

        return new ExtendedHttpToHttp2ConnectionHandler.Builder().frameListener(listener)
                                                                .sessionListener(sessionListener).build(conn);
    }

    /**
     * CD-116 : HttpToHttp2ConnectionHandler does not close channel after send GOWAY. I found stream 1
     * (upgrade request) always remained as HALF_CLOSE status. It cause channel cannot be inactive even sent
     * GOWAY. Bacause Server doesn't send response about stream 1. We workaround this problem by extend
     * HttpToHttp2ConnectionHandler.
     */
    private static final Http2StreamVisitor closeAllStreams = stream -> {
        if (stream.state() != State.CLOSED) {
            stream.close();
        }
        return true;
    };

    private static final class ExtendedHttpToHttp2ConnectionHandler extends HttpToHttp2ConnectionHandler {

        private final SessionListener sessionListener;

        static final class Builder extends BuilderBase<HttpToHttp2ConnectionHandler, Builder> {
            private SessionListener sessionListener;

            public Builder sessionListener(SessionListener sessionListener) {
                this.sessionListener = sessionListener;
                return this;
            }

            @Override
            protected HttpToHttp2ConnectionHandler build0(Http2ConnectionDecoder decoder,
                                                          Http2ConnectionEncoder encoder) {
                return new ExtendedHttpToHttp2ConnectionHandler(decoder, encoder, initialSettings(),
                                                               isValidateHeaders(), sessionListener);
            }
        }

        ExtendedHttpToHttp2ConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                            Http2Settings initialSettings, boolean validateHeaders,
                                            SessionListener sessionListener) {
            super(decoder, encoder, initialSettings, validateHeaders);
            this.sessionListener = sessionListener;
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            //mark channel session inactive due to prevent reuse on channel pool
            sessionListener.sessionDeactivated(ctx);

            //TODO(inch772) : When https://github.com/netty/netty/issues/4210 has be resolved, remove this
            encoder().connection().forEachActiveStream(closeAllStreams);
            super.close(ctx, promise);
        }
    }

    /**
     * This class for override onGoAwayRead. It is to prevent the connection from being reused by the pool
     * when received goaway.
     */
    private static class ExtendedInboundHttp2ToHttpAdapter extends InboundHttp2ToHttpAdapter {

        SessionListener sessionListener;

        protected ExtendedInboundHttp2ToHttpAdapter(Builder builder) {
            super(builder);
            sessionListener = builder.sessionListener;
        }

        public static class Builder extends InboundHttp2ToHttpAdapter.Builder {

            private final Http2Connection connection;
            private SessionListener sessionListener;

            Builder(Http2Connection connection) {
                super(connection);
                this.connection = connection;
            }

            public Builder sessionEventListener(SessionListener sessionListener) {
                this.sessionListener = sessionListener;
                return this;
            }

            @Override
            public InboundHttp2ToHttpAdapter build() {
                InboundHttp2ToHttpAdapter listener = new ExtendedInboundHttp2ToHttpAdapter(this);
                connection.addListener(listener);
                return listener;
            }
        }

        @Override
        public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData)
                throws Http2Exception {
            super.onGoAwayRead(ctx, lastStreamId, errorCode, debugData);

            //mark channel session inactive due to prevent reuse on channel pool
            if (sessionListener != null) {
                sessionListener.sessionDeactivated(ctx);
            }
        }

    }

    @FunctionalInterface
    interface ChannelHandlerProvider {
        ChannelHandler[] handlers();
    }
}