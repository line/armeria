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

import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.AbstractHttpToHttp2ConnectionHandler;
import com.linecorp.armeria.common.http.Http1ClientCodec;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent;
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
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.AsciiString;

class HttpConfigurator extends ChannelDuplexHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpConfigurator.class);

    private enum HttpPreference {
        HTTP1_REQUIRED,
        HTTP2_PREFERRED,
        HTTP2_REQUIRED
    }

    private final SslContext sslCtx;
    private final HttpPreference httpPreference;
    private final RemoteInvokerOptions options;
    private InetSocketAddress remoteAddress;

    HttpConfigurator(SessionProtocol sessionProtocol, RemoteInvokerOptions options) {
        switch (sessionProtocol) {
        case HTTP:
        case HTTPS:
            httpPreference = HttpPreference.HTTP2_PREFERRED;
            break;
        case H1:
        case H1C:
            httpPreference = HttpPreference.HTTP1_REQUIRED;
            break;
        case H2:
        case H2C:
            httpPreference = HttpPreference.HTTP2_REQUIRED;
            break;
        default:
            // Should never reach here.
            throw new Error();
        }

        this.options = requireNonNull(options, "options");

        if (sessionProtocol.isTls()) {
            try {
                SslContextBuilder builder = SslContextBuilder.forClient();
                options.trustManagerFactory().ifPresent(builder::trustManager);

                if (httpPreference == HttpPreference.HTTP2_REQUIRED ||
                    httpPreference == HttpPreference.HTTP2_PREFERRED) {

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
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                        ChannelPromise promise) throws Exception {

        // Remember the requested remote address for later use.
        this.remoteAddress = (InetSocketAddress) remoteAddress;

        // Configure the pipeline.
        final Channel ch = ctx.channel();
        try {
            if (sslCtx != null) {
                configureAsHttps(ch);
            } else {
                configureAsHttp(ch);
            }
        } catch (Throwable t) {
            promise.tryFailure(t);
            ctx.close();
        } finally {
            final ChannelPipeline pipeline = ch.pipeline();
            if (pipeline.context(this) != null) {
                pipeline.remove(this);
            }
        }

        ctx.connect(remoteAddress, localAddress, promise);
    }


    // refer https://http2.github.io/http2-spec/#discover-https
    private void configureAsHttps(Channel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        SslHandler sslHandler = sslCtx.newHandler(ch.alloc());
        pipeline.addLast(sslHandler);
        pipeline.addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (!(evt instanceof SslHandshakeCompletionEvent)) {
                    ctx.fireUserEventTriggered(evt);
                    return;
                }

                final SslHandshakeCompletionEvent handshakeEvent = (SslHandshakeCompletionEvent) evt;
                if (!handshakeEvent.isSuccess()) {
                    // The connection will be closed automatically by SslHandler.
                    return;
                }

                final SessionProtocol protocol;
                if (isHttp2Protocol(sslHandler)) {
                    if (httpPreference == HttpPreference.HTTP1_REQUIRED) {
                        finishWithNegotiationFailure(ctx, H1, H2, "unexpected protocol negotiation result");
                        return;
                    }

                    addBeforeSessionHandler(pipeline, newHttp2ConnectionHandler());
                    protocol = H2;
                } else {
                    if (httpPreference != HttpPreference.HTTP1_REQUIRED) {
                        SessionProtocolNegotiationCache.setUnsupported(ctx.channel().remoteAddress(), H2);
                    }

                    if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                        finishWithNegotiationFailure(ctx, H2, H1, "unexpected protocol negotiation result");
                        return;
                    }

                    addBeforeSessionHandler(pipeline, newHttp1Codec());
                    protocol = H1;
                }
                finishSuccessfully(pipeline, protocol);
                pipeline.remove(this);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                Exceptions.logIfUnexpected(logger, ctx.channel(), cause);
                ctx.close();
            }
        });
    }

    // refer https://http2.github.io/http2-spec/#discover-http
    private void configureAsHttp(Channel ch) {
        final ChannelPipeline pipeline = ch.pipeline();

        final boolean attemptUpgrade;
        switch (httpPreference) {
        case HTTP1_REQUIRED:
            attemptUpgrade = false;
            break;
        case HTTP2_PREFERRED:
            attemptUpgrade = !SessionProtocolNegotiationCache.isUnsupported(remoteAddress, H2C);
            break;
        case HTTP2_REQUIRED:
            attemptUpgrade = true;
            break;
        default:
            // Should never reach here.
            throw new Error();
        }

        if (attemptUpgrade) {
            Http1ClientCodec http1Codec = newHttp1Codec();
            Http2ClientUpgradeCodec http2ClientUpgradeCodec =
                    new Http2ClientUpgradeCodec(newHttp2ConnectionHandler());
            HttpClientUpgradeHandler http2UpgradeHandler =
                    new HttpClientUpgradeHandler(http1Codec, http2ClientUpgradeCodec, options.maxFrameLength());

            pipeline.addLast(http1Codec);
            pipeline.addLast(new WorkaroundHandler());
            pipeline.addLast(http2UpgradeHandler);
            pipeline.addLast(new UpgradeRequestHandler());
        } else {
            pipeline.addLast(newHttp1Codec());

            // NB: We do not call finishSuccessfully() immediately here
            //     because it assumes HttpSessionHandler to be in the pipeline,
            //     which is only true after the connection attempt is successful.
            pipeline.addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    ctx.pipeline().remove(this);
                    finishSuccessfully(pipeline, H1C);
                    ctx.fireChannelActive();
                }
            });
        }
    }

    // FIXME: Ensure unnecessary handlers are all removed from the pipeline for all protocol types.
    void finishSuccessfully(ChannelPipeline pipeline, SessionProtocol protocol) {

        switch (protocol) {
        case H1:
        case H1C:
            addBeforeSessionHandler(pipeline, new HttpObjectAggregator(options.maxFrameLength()));
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
            if (protocol == H2 || protocol == H2C) {
                timeoutHandler = new Http2ClientIdleTimeoutHandler(idleTimeoutMillis);
            } else {
                // Note: We should not use Http2ClientIdleTimeoutHandler for HTTP/1 connections,
                //       because we cannot tell if the headers defined in ExtensionHeaderNames such as
                //       'x-http2-stream-id' have been set by us or a malicious server.
                timeoutHandler = new HttpClientIdleTimeoutHandler(idleTimeoutMillis);
            }
            addBeforeSessionHandler(pipeline, timeoutHandler);
        }

        pipeline.channel().eventLoop().execute(() -> pipeline.fireUserEventTriggered(protocol));
    }

    void addBeforeSessionHandler(ChannelPipeline pipeline, ChannelHandler handler) {
        // Get the name of the HttpSessionHandler so that we can put our handlers before it.
        final String sessionHandlerName = pipeline.context(HttpSessionHandler.class).name();
        pipeline.addBefore(sessionHandlerName, null, handler);
    }

    void finishWithNegotiationFailure(
            ChannelHandlerContext ctx, SessionProtocol expected, SessionProtocol actual, String reason) {

        final ChannelPipeline pipeline = ctx.pipeline();
        pipeline.channel().eventLoop().execute(
                () -> pipeline.fireUserEventTriggered(
                        new SessionProtocolNegotiationException(expected, actual, reason)));
        ctx.close();
    }

    boolean isHttp2Protocol(SslHandler sslHandler) {
        return ApplicationProtocolNames.HTTP_2.equals(sslHandler.applicationProtocol());
    }

    /**
     * A handler that triggers the cleartext upgrade to HTTP/2 by sending an initial HTTP request.
     */
    private final class UpgradeRequestHandler extends ChannelDuplexHandler {

        private UpgradeEvent upgradeEvt;
        private FullHttpResponse upgradeRes;

        /**
         * Sends the initial upgrade request, which is {@code "HEAD / HTTP/1.1"}
         */
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            final FullHttpRequest upgradeReq =
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/");

            // Note: There's no need to fill Connection, Upgrade, and HTTP2-Settings headers here
            //       because they are filled by Http2ClientUpgradeCodec.

            final String host = HttpHostHeaderUtil.hostHeader(
                    remoteAddress.getHostString(), remoteAddress.getPort(), sslCtx != null);

            upgradeReq.headers().set(HttpHeaderNames.HOST, host);

            ctx.writeAndFlush(upgradeReq);
            ctx.fireChannelActive();
        }

        /**
         * Keeps the upgrade result in {@link #upgradeEvt}.
         */
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (!(evt instanceof UpgradeEvent)) {
                ctx.fireUserEventTriggered(evt);
                return;
            }

            final UpgradeEvent upgradeEvt = (UpgradeEvent) evt;
            if (upgradeEvt == UpgradeEvent.UPGRADE_ISSUED) {
                // Uninterested in this event
                return;
            }

            this.upgradeEvt = upgradeEvt;
            onUpgradeResult(ctx);
        }

        /**
         * Waits until the upgrade response is received, and performs the final configuration of the pipeline
         * based on the upgrade result.
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (upgradeRes == null && msg instanceof FullHttpResponse) {
                final FullHttpResponse res = (FullHttpResponse) msg;
                final HttpHeaders headers = res.headers();
                final String streamId = headers.get(ExtensionHeaderNames.STREAM_ID.text());
                if (streamId == null || "1".equals(streamId)) {
                    // Received the response for the upgrade request sent in channelActive().
                    res.release();
                    upgradeRes = res;
                    onUpgradeResult(ctx);
                    return;
                }
            }

            ctx.fireChannelRead(msg);
        }

        private void onUpgradeResult(ChannelHandlerContext ctx) {
            final UpgradeEvent upgradeEvt = this.upgradeEvt;
            final FullHttpResponse upgradeRes = this.upgradeRes;
            if (upgradeEvt == null || upgradeRes == null) {
                return;
            }

            final ChannelPipeline p = ctx.pipeline();

            // Done with this handler, remove it from the pipeline.
            p.remove(this);

            if ("close".equalsIgnoreCase(upgradeRes.headers().get(HttpHeaderNames.CONNECTION))) {
                // Server wants us to close the connection, which means we cannot use this connection
                // to send the request that contains the actual invocation.
                SessionProtocolNegotiationCache.setUnsupported(ctx.channel().remoteAddress(), H2C);

                if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                    finishWithNegotiationFailure(ctx, H2C, H1C,
                                                 "upgrade response with 'Connection: close' header");
                } else {
                    // We can silently retry with H1C.
                    retryWithH1C(ctx);
                }
                return;
            }

            switch (upgradeEvt) {
            case UPGRADE_SUCCESSFUL:
                finishSuccessfully(p, H2C);
                break;
            case UPGRADE_REJECTED:
                SessionProtocolNegotiationCache.setUnsupported(ctx.channel().remoteAddress(), H2C);

                if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                    finishWithNegotiationFailure(ctx, H2C, H1C, "upgrade request rejected");
                    return;
                }

                finishSuccessfully(p, H1C);
                break;
            default:
                // Should never reach here.
                throw new Error();
            }
        }

        private void retryWithH1C(ChannelHandlerContext ctx) {
            final ChannelPipeline pipeline = ctx.pipeline();
            pipeline.channel().eventLoop().execute(
                    () -> pipeline.fireUserEventTriggered(HttpSessionChannelFactory.RETRY_WITH_H1C));
            ctx.close();
        }
    }

    private Http2ConnectionHandler newHttp2ConnectionHandler() {
        final boolean validateHeaders = false;
        final Http2Connection conn = new DefaultHttp2Connection(false);
        final InboundHttp2ToHttpAdapter listener = new InboundHttp2ToHttpAdapterBuilder(conn)
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
            public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
                HttpSessionHandler.get(ctx.channel()).deactivate();
                super.close(ctx, promise);
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
            HttpSessionHandler.get(ctx.channel()).deactivate();
        }
    }

    /**
     * Workaround handler for interoperability with Jetty.
     * - Jetty performs case-sensitive comparison for the Connection header value. (upgrade vs Upgrade)
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
