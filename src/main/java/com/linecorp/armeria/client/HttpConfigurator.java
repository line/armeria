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
import com.linecorp.armeria.common.http.AbstractHttpToHttp2ConnectionHandler;
import com.linecorp.armeria.common.http.Http1ClientCodec;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.AsciiString;

class HttpConfigurator extends ChannelInitializer<Channel> {

    private static final Logger logger = LoggerFactory.getLogger(HttpConfigurator.class);

    private static final Set<SessionProtocol> http2preferredProtocols = EnumSet.of(SessionProtocol.H2,
                                                                                   SessionProtocol.H2C,
                                                                                   SessionProtocol.HTTP,
                                                                                   SessionProtocol.HTTPS);
    private final SslContext sslCtx;
    private final boolean isHttp2Preferred;
    private final String host;
    private final RemoteInvokerOptions options;

    HttpConfigurator(SessionProtocol sessionProtocol, String host, RemoteInvokerOptions options) {
        isHttp2Preferred = http2preferredProtocols.contains(sessionProtocol);
        this.host = requireNonNull(host, "host");
        this.options = requireNonNull(options, "options");

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
                    final SessionProtocol protocol;
                    if (event.isSuccess()) {
                        if (isHttp2Protocol(sslHandler)) {
                            pipeline.addLast(newHttp2ConnectionHandler());
                            protocol = SessionProtocol.H2;
                        } else {
                            pipeline.addLast(newHttp1Codec());
                            protocol = SessionProtocol.H1;
                        }
                        finishConfiguration(pipeline, protocol);
                    }
                    pipeline.remove(this);
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

    // refer https://http2.github.io/http2-spec/#discover-http
    private void configureAsHttp(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        if (isHttp2Preferred) {
            Http1ClientCodec http1Codec = newHttp1Codec();
            Http2ClientUpgradeCodec http2ClientUpgradeCodec =
                    new Http2ClientUpgradeCodec(newHttp2ConnectionHandler());
            HttpClientUpgradeHandler http2UpgradeHandler =
                    new HttpClientUpgradeHandler(http1Codec, http2ClientUpgradeCodec, options.maxFrameLength());

            pipeline.addLast(http1Codec);
            pipeline.addLast(new WorkaroundHandler());
            pipeline.addLast(http2UpgradeHandler);
            pipeline.addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                    if (evt instanceof HttpClientUpgradeHandler.UpgradeEvent) {
                        switch ((HttpClientUpgradeHandler.UpgradeEvent) evt) {
                        case UPGRADE_SUCCESSFUL:
                            finishConfiguration(pipeline, SessionProtocol.H2C);
                            pipeline.remove(this);
                            break;
                        case UPGRADE_REJECTED:
                            // FIXME(trustin): Handle critical status codes such as 400.
                            finishConfiguration(pipeline, SessionProtocol.H1C);
                            pipeline.remove(this);
                            break;
                        }
                    }
                    ctx.fireUserEventTriggered(evt);
                }
            });
            pipeline.addLast(new UpgradeRequestHandler(host));
        } else {
            pipeline.addLast(newHttp1Codec());
            finishConfiguration(pipeline, SessionProtocol.H1C);
        }
    }

    void finishConfiguration(ChannelPipeline pipeline, SessionProtocol protocol) {
        switch (protocol) {
        case H1:
        case H1C:
            pipeline.addLast(new HttpObjectAggregator(options.maxFrameLength()));
            break;
        case H2:
        case H2C:
            // HTTP/2 does not require the aggregator because
            // InboundHttp2ToHttpAdapter always creates a FullHttpRequest.
            break;
        default:
            // Should never reach here.
            throw new Error();
        }

        final long idleTimeoutMillis = options.idleTimeoutMillis();
        if (idleTimeoutMillis > 0) {
            final HttpClientIdleTimeoutHandler timeoutHandler;
            if (protocol == SessionProtocol.H2 || protocol == SessionProtocol.H2C) {
                timeoutHandler = new Http2ClientIdleTimeoutHandler(idleTimeoutMillis);
            } else {
                // Note: We should not use Http2ClientIdleTimeoutHandler for HTTP/1 connections,
                //       because we cannot tell if the headers defined in ExtensionHeaderNames such as
                //       'x-http2-stream-id' have been set by us or a malicious server.
                timeoutHandler = new HttpClientIdleTimeoutHandler(idleTimeoutMillis);
            }
            pipeline.addLast(timeoutHandler);
        }
        pipeline.addLast(new HttpSessionHandler(protocol));
        pipeline.channel().eventLoop().execute(() -> pipeline.fireUserEventTriggered(protocol));
    }

    protected boolean isHttp2Protocol(SslHandler sslHandler) {
        return ApplicationProtocolNames.HTTP_2.equals(sslHandler.applicationProtocol());
    }

    /**
     * A handler that triggers the cleartext upgrade to HTTP/2 by sending an initial HTTP request.
     */
    private static final class UpgradeRequestHandler extends ChannelInboundHandlerAdapter {

        private final String host;

        UpgradeRequestHandler(String host) {
            this.host = host;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            final FullHttpRequest upgradeReq =
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/");
            final HttpHeaders headers = upgradeReq.headers();

            // Note: There's no need to fill Connection, Upgrade, and HTTP2-Settings headers here
            //       because they are filled by Http2ClientUpgradeCodec.
            headers.set(HttpHeaderNames.HOST, host);

            ctx.writeAndFlush(upgradeReq);
            ctx.fireChannelActive();

            // Done with this handler, remove it from the pipeline.
            ctx.pipeline().remove(this);
        }
    }

    private Http2ConnectionHandler newHttp2ConnectionHandler() {
        final boolean validateHeaders = false;
        final Http2Connection conn = new DefaultHttp2Connection(false);
        final InboundHttp2ToHttpAdapter listener = new InboundHttp2ToHttpAdapter.Builder(conn)
                .propagateSettings(true).validateHttpHeaders(validateHeaders)
                .maxContentLength(options.maxFrameLength()).build();

        Http2FrameReader reader = new DefaultHttp2FrameReader(validateHeaders);
        Http2FrameWriter writer = new DefaultHttp2FrameWriter();

        Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(conn, writer);
        Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(conn, encoder, reader);

        final HttpToHttp2ClientConnectionHandler handler =
                new HttpToHttp2ClientConnectionHandler(
                        decoder, encoder, new Http2Settings(), validateHeaders);

        // Setup post build options
        handler.gracefulShutdownTimeoutMillis(options.idleTimeoutMillis());
        handler.decoder().frameListener(listener);

        return handler;
    }

    private static Http1ClientCodec newHttp1Codec() {
        return new Http1ClientCodec() {
            @Override
            protected void onCloseRequest(ChannelHandlerContext ctx) throws Exception {
                HttpSessionHandler.deactivate(ctx.channel());
            }
        };
    }

    private static final class HttpToHttp2ClientConnectionHandler extends AbstractHttpToHttp2ConnectionHandler {

        HttpToHttp2ClientConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                           Http2Settings initialSettings, boolean validateHeaders) {
            super(decoder, encoder, initialSettings, validateHeaders);
        }

        @Override
        protected void onCloseRequest(ChannelHandlerContext ctx) throws Exception {
            HttpSessionHandler.deactivate(ctx.channel());
        }
    }

    /**
     * Workaround handler for interoperability with Jetty.
     * - Jetty performs case-sensitive comparision for the Connection header value. (upgrade vs Upgrade)
     * - Jetty does not send 'Upgrade: h2c' header in its 101 Switching Protocol response.
     */
    private static final class WorkaroundHandler extends ChannelDuplexHandler {

        private static final AsciiString CONNECTION_VALUE = new AsciiString("HTTP2-Settings,Upgrade");

        private boolean needsToFilterUpgradeResponse = true;
        private boolean needsToFilterUpgradeRequest = true;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (needsToFilterUpgradeResponse && msg instanceof HttpResponse) {
                needsToFilterUpgradeResponse = false;
                final HttpResponse res = (HttpResponse) msg;
                if (res.status().code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
                    final HttpHeaders headers = res.headers();
                    if (!headers.contains(HttpHeaderNames.UPGRADE)) {
                        headers.set(HttpHeaderNames.UPGRADE,
                                    Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME);
                    }
                }

                if (!needsToFilterUpgradeRequest) {
                    ctx.pipeline().remove(this);
                }
            }

            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (needsToFilterUpgradeRequest) {
                needsToFilterUpgradeRequest = false;
                final FullHttpRequest req = (FullHttpRequest) msg;
                req.headers().set(HttpHeaderNames.CONNECTION, CONNECTION_VALUE);

                if (!needsToFilterUpgradeResponse) {
                    ctx.pipeline().remove(this);
                }
            }

            super.write(ctx, msg, promise);
        }
    }
}
