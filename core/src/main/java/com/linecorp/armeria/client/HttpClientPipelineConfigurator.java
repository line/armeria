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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_REJECTED;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.Http1ClientCodec;
import com.linecorp.armeria.internal.Http2GoAwayListener;
import com.linecorp.armeria.internal.ReadSuppressingHandler;
import com.linecorp.armeria.internal.TrafficLoggingHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
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
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

final class HttpClientPipelineConfigurator extends ChannelDuplexHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientPipelineConfigurator.class);

    /**
     * The maximum allowed content length of an HTTP/1 to 2 upgrade response.
     */
    private static final long UPGRADE_RESPONSE_MAX_LENGTH = 16384;

    private enum HttpPreference {
        HTTP1_REQUIRED,
        HTTP2_PREFERRED,
        HTTP2_REQUIRED
    }

    private final HttpClientFactory clientFactory;
    @Nullable
    private final SslContext sslCtx;
    private final HttpPreference httpPreference;
    @Nullable
    private InetSocketAddress remoteAddress;

    HttpClientPipelineConfigurator(HttpClientFactory clientFactory, SessionProtocol sessionProtocol) {
        this.clientFactory = clientFactory;

        if (sessionProtocol == HTTP || sessionProtocol == HTTPS) {
            httpPreference = HttpPreference.HTTP2_PREFERRED;
        } else if (sessionProtocol == H1 || sessionProtocol == H1C) {
            httpPreference = HttpPreference.HTTP1_REQUIRED;
        } else if (sessionProtocol == H2 || sessionProtocol == H2C) {
            httpPreference = HttpPreference.HTTP2_REQUIRED;
        } else {
            // Should never reach here.
            throw new Error();
        }

        if (sessionProtocol.isTls()) {
            try {
                final SslContextBuilder builder = SslContextBuilder.forClient();

                builder.sslProvider(
                        Flags.useOpenSsl() ? SslProvider.OPENSSL : SslProvider.JDK);
                clientFactory.sslContextCustomizer().accept(builder);

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
                throw new IllegalStateException("failed to create an SslContext", e);
            }
        } else {
            sslCtx = null;
        }
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                        ChannelPromise promise) throws Exception {

        // Remember the requested remote address for later use.
        final InetSocketAddress inetRemoteAddr = (InetSocketAddress) remoteAddress;
        this.remoteAddress = inetRemoteAddr;

        // Configure the pipeline.
        final Channel ch = ctx.channel();

        final ChannelPipeline p = ch.pipeline();
        p.addLast(new FlushConsolidationHandler());
        p.addLast(ReadSuppressingHandler.INSTANCE);

        try {
            if (sslCtx != null) {
                configureAsHttps(ch, inetRemoteAddr);
            } else {
                configureAsHttp(ch);
            }
        } catch (Throwable t) {
            promise.tryFailure(t);
            ctx.close();
        } finally {
            if (p.context(this) != null) {
                p.remove(this);
            }
        }

        ctx.connect(remoteAddress, localAddress, promise);
    }

    /**
     * See <a href="https://http2.github.io/http2-spec/#discover-https">HTTP/2 specification</a>.
     */
    private void configureAsHttps(Channel ch, InetSocketAddress remoteAddr) {
        assert sslCtx != null;

        final ChannelPipeline p = ch.pipeline();
        final SslHandler sslHandler = sslCtx.newHandler(ch.alloc(),
                                                        remoteAddr.getHostString(),
                                                        remoteAddr.getPort());
        p.addLast(sslHandler);
        p.addLast(TrafficLoggingHandler.CLIENT);
        p.addLast(new ChannelInboundHandlerAdapter() {
            private boolean handshakeFailed;

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (!(evt instanceof SslHandshakeCompletionEvent)) {
                    ctx.fireUserEventTriggered(evt);
                    return;
                }

                final SslHandshakeCompletionEvent handshakeEvent = (SslHandshakeCompletionEvent) evt;
                if (!handshakeEvent.isSuccess()) {
                    // The connection will be closed automatically by SslHandler.
                    logger.warn("{} TLS handshake failed:", ctx.channel(), handshakeEvent.cause());
                    handshakeFailed = true;
                    return;
                }

                final SessionProtocol protocol;
                if (isHttp2Protocol(sslHandler)) {
                    if (httpPreference == HttpPreference.HTTP1_REQUIRED) {
                        finishWithNegotiationFailure(ctx, H1, H2, "unexpected protocol negotiation result");
                        return;
                    }

                    addBeforeSessionHandler(p, newHttp2ConnectionHandler(ch));
                    protocol = H2;
                } else {
                    if (httpPreference != HttpPreference.HTTP1_REQUIRED) {
                        SessionProtocolNegotiationCache.setUnsupported(ctx.channel().remoteAddress(), H2);
                    }

                    if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                        finishWithNegotiationFailure(ctx, H2, H1, "unexpected protocol negotiation result");
                        return;
                    }

                    addBeforeSessionHandler(p, newHttp1Codec(
                            clientFactory.maxHttp1InitialLineLength(),
                            clientFactory.maxHttp1HeaderSize(),
                            clientFactory.maxHttp1ChunkSize()));
                    protocol = H1;
                }
                finishSuccessfully(p, protocol);
                p.remove(this);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                if (handshakeFailed &&
                    cause instanceof DecoderException &&
                    cause.getCause() instanceof SSLException) {
                    // Swallow an SSLException raised after handshake failure.
                    return;
                }

                Exceptions.logIfUnexpected(logger, ctx.channel(), cause);
                ctx.close();
            }
        });
    }

    // refer https://http2.github.io/http2-spec/#discover-http
    private void configureAsHttp(Channel ch) {
        final ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(TrafficLoggingHandler.CLIENT);

        final boolean attemptUpgrade;
        switch (httpPreference) {
        case HTTP1_REQUIRED:
            attemptUpgrade = false;
            break;
        case HTTP2_PREFERRED:
            assert remoteAddress != null;
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
            final Http2ClientConnectionHandler http2Handler = newHttp2ConnectionHandler(ch);
            if (clientFactory.useHttp2Preface()) {
                pipeline.addLast(new DowngradeHandler());
                pipeline.addLast(http2Handler);
            } else {
                final Http1ClientCodec http1Codec = newHttp1Codec(
                        clientFactory.maxHttp1InitialLineLength(),
                        clientFactory.maxHttp1HeaderSize(),
                        clientFactory.maxHttp1ChunkSize());
                final Http2ClientUpgradeCodec http2ClientUpgradeCodec =
                        new Http2ClientUpgradeCodec(http2Handler);
                final HttpClientUpgradeHandler http2UpgradeHandler =
                        new HttpClientUpgradeHandler(
                                http1Codec, http2ClientUpgradeCodec,
                                (int) Math.min(Integer.MAX_VALUE, UPGRADE_RESPONSE_MAX_LENGTH));

                pipeline.addLast(http1Codec);
                pipeline.addLast(new WorkaroundHandler());
                pipeline.addLast(http2UpgradeHandler);
                pipeline.addLast(new UpgradeRequestHandler(http2Handler.responseDecoder()));
            }
        } else {
            pipeline.addLast(newHttp1Codec(
                    clientFactory.maxHttp1InitialLineLength(),
                    clientFactory.maxHttp1HeaderSize(),
                    clientFactory.maxHttp1ChunkSize()));

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

        if (protocol == H1 || protocol == H1C) {
            addBeforeSessionHandler(pipeline, new Http1ResponseDecoder(pipeline.channel()));
        } else if (protocol == H2 || protocol == H2C) {
            final int initialWindow = clientFactory.initialHttp2ConnectionWindowSize();
            if (initialWindow > DEFAULT_WINDOW_SIZE) {
                incrementLocalWindowSize(pipeline, initialWindow - DEFAULT_WINDOW_SIZE);
            }
        }

        final long idleTimeoutMillis = clientFactory.idleTimeoutMillis();
        if (idleTimeoutMillis > 0) {
            pipeline.addFirst(new HttpClientIdleTimeoutHandler(idleTimeoutMillis));
        }

        pipeline.channel().eventLoop().execute(() -> pipeline.fireUserEventTriggered(protocol));
    }

    private static void incrementLocalWindowSize(ChannelPipeline pipeline, int delta) {
        try {
            final Http2Connection connection = pipeline.get(Http2ClientConnectionHandler.class).connection();
            connection.local().flowController().incrementWindowSize(connection.connectionStream(), delta);
        } catch (Http2Exception e) {
            logger.warn("Failed to increment local flowController window size: {}", delta, e);
        }
    }

    void addBeforeSessionHandler(ChannelPipeline pipeline, ChannelHandler handler) {
        // Get the name of the HttpSessionHandler so that we can put our handlers before it.
        final ChannelHandlerContext lastContext = pipeline.lastContext();
        assert lastContext.handler().getClass() == HttpSessionHandler.class;

        pipeline.addBefore(lastContext.name(), null, handler);
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
    private final class UpgradeRequestHandler extends ChannelInboundHandlerAdapter {

        private final Http2ResponseDecoder responseDecoder;
        @Nullable
        private UpgradeEvent upgradeEvt;
        private boolean needsToClose;

        UpgradeRequestHandler(Http2ResponseDecoder responseDecoder) {
            this.responseDecoder = responseDecoder;
        }

        /**
         * Sends the initial upgrade request, which is {@code "HEAD / HTTP/1.1"}.
         */
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            final FullHttpRequest upgradeReq =
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, "/");

            // Note: There's no need to fill Connection, Upgrade, and HTTP2-Settings headers here
            //       because they are filled by Http2ClientUpgradeCodec.

            assert remoteAddress != null;
            final String host = HttpHeaderUtil.hostHeader(
                    remoteAddress.getHostString(), remoteAddress.getPort(), H1C.defaultPort());

            upgradeReq.headers().set(HttpHeaderNames.HOST, host);
            upgradeReq.headers().set(HttpHeaderNames.USER_AGENT, HttpHeaderUtil.USER_AGENT);

            ctx.writeAndFlush(upgradeReq);

            final Http2ResponseDecoder responseDecoder = this.responseDecoder;
            final DecodedHttpResponse res = new DecodedHttpResponse(ctx.channel().eventLoop());

            res.init(responseDecoder.inboundTrafficController());
            res.subscribe(new Subscriber<HttpObject>() {

                private boolean notified;

                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject o) {
                    if (notified) {
                        // Discard the first response.
                        return;
                    }

                    notified = true;
                    assert upgradeEvt == UpgradeEvent.UPGRADE_SUCCESSFUL;
                    onUpgradeResponse(ctx, true);
                }

                @Override
                public void onError(Throwable t) {
                    ctx.fireExceptionCaught(t);
                }

                @Override
                public void onComplete() {}
            });

            // NB: No need to set the response timeout because we have session creation timeout.
            responseDecoder.addResponse(0, null, res, RequestLogBuilder.NOOP, 0, UPGRADE_RESPONSE_MAX_LENGTH);
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
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            boolean handled = false;
            if (msg instanceof HttpResponse) {
                // The server rejected the upgrade request and sent its response in HTTP/1.
                assert upgradeEvt == UPGRADE_REJECTED;
                final String connection = ((HttpResponse) msg).headers().get(HttpHeaderNames.CONNECTION);
                needsToClose = connection != null && Ascii.equalsIgnoreCase("close", connection);
                handled = true;
            }

            if (msg instanceof HttpContent) {
                if (msg instanceof LastHttpContent) {
                    // Received the rejecting response completely.
                    onUpgradeResponse(ctx, false);
                }
                handled = true;
            }

            if (!handled) {
                ctx.fireChannelRead(msg);
            } else {
                ReferenceCountUtil.release(msg);
            }
        }

        private void onUpgradeResponse(ChannelHandlerContext ctx, boolean success) {
            final UpgradeEvent upgradeEvt = this.upgradeEvt;
            assert upgradeEvt != null : "received an upgrade response before an UpgradeEvent";

            final ChannelPipeline p = ctx.pipeline();

            // Done with this handler, remove it from the pipeline.
            p.remove(this);

            if (needsToClose) {
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

            if (success) {
                finishSuccessfully(p, H2C);
            } else {
                SessionProtocolNegotiationCache.setUnsupported(ctx.channel().remoteAddress(), H2C);

                if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                    finishWithNegotiationFailure(ctx, H2C, H1C, "upgrade request rejected");
                    return;
                }

                finishSuccessfully(p, H1C);
            }
        }
    }

    /**
     * A handler that closes an HTTP/2 connection when the server responds with an HTTP/1 response, so that
     * HTTP/1 is used instead of HTTP/2 on next connection attempt.
     */
    private final class DowngradeHandler extends ByteToMessageDecoder {

        private boolean handledResponse;

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (in.readableBytes() < Http2CodecUtil.FRAME_HEADER_LENGTH) {
                return;
            }

            handledResponse = true;

            final ChannelPipeline p = ctx.pipeline();

            if (!isSettingsFrame(in)) { // The first frame must be a settings frame.
                // Http2ConnectionHandler sent the connection preface, but the server responded with
                // something else, which means the server does not support HTTP/2.
                SessionProtocolNegotiationCache.setUnsupported(ctx.channel().remoteAddress(), H2C);
                if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                    finishWithNegotiationFailure(
                            ctx, H2C, H1C, "received a non-HTTP/2 response for the HTTP/2 connection preface");
                } else {
                    // We can silently retry with H1C.
                    retryWithH1C(ctx);
                }

                // We are going to close the connection really soon, so we don't need the response.
                in.skipBytes(in.readableBytes());
            } else {
                // The server responded with a non-HTTP/1 response. Continue treating the connection as HTTP/2.
                finishSuccessfully(p, H2C);
            }

            p.remove(this);
        }

        private boolean isSettingsFrame(ByteBuf in) {
            final int start = in.readerIndex();
            return in.getByte(start + 3) == 4 &&             // type == SETTINGS
                   (in.getInt(start + 5) & 0x7FFFFFFF) == 0; // streamId == 0
        }

        @Override
        protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            super.decodeLast(ctx, in, out);
            if (!handledResponse) {
                // If the connection has been closed even without receiving anything useful,
                // it is likely that the server failed to decode the preface string.
                if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                    finishWithNegotiationFailure(ctx, H2C, H1C,
                                                 "too little data to determine the HTTP version");
                } else {
                    // We can silently retry with H1C.
                    retryWithH1C(ctx);
                }
            }
        }
    }

    static void retryWithH1C(ChannelHandlerContext ctx) {
        HttpSession.get(ctx.channel()).retryWithH1C();
        ctx.close();
    }

    private Http2ClientConnectionHandler newHttp2ConnectionHandler(Channel ch) {
        final boolean validateHeaders = false;
        final Http2Connection conn = new DefaultHttp2Connection(false);
        conn.addListener(new Http2GoAwayListener(ch));

        final Http2FrameReader reader = new DefaultHttp2FrameReader(validateHeaders);
        final Http2FrameWriter writer = new DefaultHttp2FrameWriter();

        final Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(conn, writer);
        final Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(conn, encoder, reader);

        final Http2Settings http2Settings = http2Settings();

        final Http2ResponseDecoder listener = new Http2ResponseDecoder(conn, ch, encoder);
        final Http2ClientConnectionHandler handler =
                new Http2ClientConnectionHandler(decoder, encoder, http2Settings, listener);
        // Setup post build options
        handler.gracefulShutdownTimeoutMillis(clientFactory.idleTimeoutMillis());

        return handler;
    }

    private Http2Settings http2Settings() {
        final Http2Settings http2Settings = new Http2Settings();
        if (clientFactory.initialHttp2StreamWindowSize() != DEFAULT_WINDOW_SIZE) {
            http2Settings.initialWindowSize(clientFactory.initialHttp2StreamWindowSize());
        }
        if (clientFactory.http2MaxFrameSize() != DEFAULT_MAX_FRAME_SIZE) {
            http2Settings.maxFrameSize(clientFactory.http2MaxFrameSize());
        }
        return http2Settings;
    }

    private static Http1ClientCodec newHttp1Codec(
            int defaultMaxInitialLineLength, int defaultMaxHeaderSize, int defaultMaxChunkSize) {
        return new Http1ClientCodec(defaultMaxInitialLineLength, defaultMaxHeaderSize, defaultMaxChunkSize) {
            @Override
            public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
                HttpSession.get(ctx.channel()).deactivate();
                super.close(ctx, promise);
            }
        };
    }

    /**
     * Workaround handler for interoperability with Jetty.
     * - Jetty performs case-sensitive comparison for the Connection header value. (upgrade vs Upgrade)
     * - Jetty does not send 'Upgrade: h2c' header in its 101 Switching Protocol response.
     */
    private static final class WorkaroundHandler extends ChannelDuplexHandler {

        private static final AsciiString CONNECTION_VALUE = AsciiString.cached("HTTP2-Settings,Upgrade");

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
