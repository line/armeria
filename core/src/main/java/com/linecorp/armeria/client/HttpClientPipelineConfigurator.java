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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.client.HttpChannelPool.TIMINGS_BUILDER_KEY;
import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static com.linecorp.armeria.internal.client.PendingExceptionUtil.setPendingException;
import static io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_REJECTED;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.Consumer;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ClientConnectionTimingsBuilder;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.client.UserAgentUtil;
import com.linecorp.armeria.internal.common.ArmeriaHttp2HeadersDecoder;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.common.ReadSuppressingHandler;
import com.linecorp.armeria.internal.common.TrafficLoggingHandler;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
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
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2InboundFrameLogger;
import io.netty.handler.codec.http2.Http2OutboundFrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

final class HttpClientPipelineConfigurator extends ChannelDuplexHandler {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientPipelineConfigurator.class);

    private static final Http2FrameLogger frameLogger =
            new Http2FrameLogger(LogLevel.TRACE, "com.linecorp.armeria.logging.traffic.client.http2");

    /**
     * The maximum allowed content length of an HTTP/1 to 2 upgrade response.
     */
    private static final long UPGRADE_RESPONSE_MAX_LENGTH = 16384;

    private static final RequestOptions REQUEST_OPTIONS_FOR_UPGRADE_REQUEST =
            RequestOptions.builder()
                          .responseTimeoutMillis(0)
                          .maxResponseLength(UPGRADE_RESPONSE_MAX_LENGTH).build();

    private enum HttpPreference {
        HTTP1_REQUIRED,
        HTTP2_PREFERRED,
        HTTP2_REQUIRED
    }

    private final HttpClientFactory clientFactory;
    private final boolean webSocket;
    @Nullable
    private final SslContext sslCtx;
    private final HttpPreference httpPreference;
    @Nullable
    private SocketAddress remoteAddress;

    private final SessionProtocol http1;
    private final SessionProtocol http2;

    HttpClientPipelineConfigurator(HttpClientFactory clientFactory,
                                   boolean webSocket, SessionProtocol sessionProtocol,
                                   @Nullable SslContext sslCtx) {
        this.clientFactory = clientFactory;
        this.webSocket = webSocket;

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
            this.sslCtx = sslCtx;
            http1 = H1;
            http2 = H2;
        } else {
            this.sslCtx = null;
            http1 = H1C;
            http2 = H2C;
        }
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                        ChannelPromise connectionPromise) throws Exception {

        // Remember the requested remote address for later use.
        this.remoteAddress = remoteAddress;

        // Configure the pipeline.
        final Channel ch = ctx.channel();
        ChannelUtil.disableWriterBufferWatermark(ch);

        final ChannelPipeline p = ch.pipeline();
        p.addLast(new FlushConsolidationHandler());
        p.addLast(ReadSuppressingAndChannelDeactivatingHandler.INSTANCE);

        try {
            if (isHttps()) {
                configureAsHttps(ch, remoteAddress);
            } else {
                configureAsHttp(ch, connectionPromise);
            }
        } catch (Throwable t) {
            connectionPromise.tryFailure(t);
            ctx.close();
            return;
        } finally {
            if (p.context(this) != null) {
                p.remove(this);
            }
        }

        ctx.connect(remoteAddress, localAddress, connectionPromise);
    }

    /**
     * See <a href="https://http2.github.io/http2-spec/#discover-https">HTTP/2 specification</a>.
     */
    private void configureAsHttps(Channel ch, SocketAddress remoteAddr) {
        assert isHttps();

        final ChannelPipeline p = ch.pipeline();
        final SSLEngine sslEngine;
        if (remoteAddr instanceof InetSocketAddress) {
            final InetSocketAddress raddr = (InetSocketAddress) remoteAddr;
            sslEngine = sslCtx.newEngine(ch.alloc(),
                                         raddr.getHostString(),
                                         raddr.getPort());
        } else {
            assert remoteAddr instanceof DomainSocketAddress : remoteAddr;
            sslEngine = sslCtx.newEngine(ch.alloc());
        }
        final ClientConnectionTimingsBuilder timingsBuilder = ch.attr(TIMINGS_BUILDER_KEY).get();
        final SslHandler sslHandler = new ClientSslHandler(sslEngine, timingsBuilder);
        p.addLast(configureSslHandler(sslHandler));
        p.addLast(TrafficLoggingHandler.CLIENT);
        p.addLast(new ChannelInboundHandlerAdapter() {
            @Nullable
            private Boolean handshakeFailed;

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (!(evt instanceof SslHandshakeCompletionEvent)) {
                    ctx.fireUserEventTriggered(evt);
                    return;
                }

                final SslHandshakeCompletionEvent handshakeEvent = (SslHandshakeCompletionEvent) evt;
                handshakeFailed = !handshakeEvent.isSuccess();
                if (handshakeFailed) {
                    // The connection will be closed automatically by SslHandler.
                    return;
                }

                if (!sslHandler.handshakeFuture().isDone()) {
                    // Ignore successful handshake events for other SslHandlers in the pipeline.
                    // This can happen when a client connects with ssl to an encrypted proxy server,
                    // and then to an encrypted backend. Without this check, clients may incorrectly
                    // conclude protocol negotiation and start a new session.
                    return;
                }

                final SessionProtocol protocol;
                if (isHttp2Protocol(sslHandler)) {
                    if (httpPreference == HttpPreference.HTTP1_REQUIRED) {
                        finishWithNegotiationFailure(ctx, H1, H2, "unexpected protocol negotiation result");
                        return;
                    }

                    addBeforeSessionHandler(p, newHttp2ConnectionHandler(ch, H2));
                    protocol = H2;
                } else if (clientFactory.useHttp2WithoutAlpn() && attemptUpgrade()) {
                    configureUpgradeCodec(ch, h -> addBeforeSessionHandler(p, h));
                    p.remove(this);
                    return;
                } else {
                    if (httpPreference != HttpPreference.HTTP1_REQUIRED) {
                        SessionProtocolNegotiationCache.setUnsupported(remoteAddress(ctx), H2);
                    }

                    if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                        finishWithNegotiationFailure(ctx, H2, H1, "unexpected protocol negotiation result");
                        return;
                    }

                    addBeforeSessionHandler(p, newHttp1Codec(
                            clientFactory.http1MaxInitialLineLength(),
                            clientFactory.http1MaxHeaderSize(),
                            clientFactory.http1MaxChunkSize()));
                    protocol = H1;
                }
                finishSuccessfully(p, protocol);
                p.remove(this);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                if (handshakeFailed == null) {
                    // An exception was raised before the handshake event was completed.
                    // A legacy HTTPS server such as Microsoft-IIS/8.5 may reset the connection
                    // if no cipher suites in common.
                    final String tlsVersion = sslHandler.engine().getSession().getProtocol();
                    final PreTlsHandshakeException preTlsHandshakeException = new PreTlsHandshakeException(
                            "An unexpected exception before a TLS handshake starts. The possible reason could" +
                            " be one of: [connection forcefully closed by peer, unsupported TLS version, " +
                            "no cipher suites in common, etc.] " +
                            "(TLS version: " + tlsVersion + ", cipher suites: " + sslCtx.cipherSuites() + ')',
                            cause);
                    setPendingException(ctx, preTlsHandshakeException);
                    return;
                }
                if (handshakeFailed &&
                    cause instanceof DecoderException &&
                    cause.getCause() instanceof SSLException) {
                    setPendingException(ctx, cause.getCause());
                    return;
                }

                Exceptions.logIfUnexpected(logger, ctx.channel(), cause);
                ctx.close();
            }
        });
    }

    /**
     * {@code channel().remoteAddress()} can return {@code null} if the connection is closed
     * before {@code channel().remoteAddress()} is called which caches the address in it.
     */
    private SocketAddress remoteAddress(ChannelHandlerContext ctx) {
        return firstNonNull(ctx.channel().remoteAddress(), remoteAddress);
    }

    /**
     * Configures the specified {@link SslHandler} with common settings.
     */
    private static SslHandler configureSslHandler(SslHandler sslHandler) {
        // Set endpoint identification algorithm so that JDK's default X509TrustManager implementation
        // performs host name checks. Without this, the X509TrustManager implementation will never raise
        // a CertificateException even if the domain name or IP address mismatches.
        final SSLEngine engine = sslHandler.engine();
        final SSLParameters params = engine.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        engine.setSSLParameters(params);
        return sslHandler;
    }

    private boolean attemptUpgrade() {
        switch (httpPreference) {
            case HTTP1_REQUIRED:
                return false;
            case HTTP2_PREFERRED:
                assert remoteAddress != null;
                return !SessionProtocolNegotiationCache.isUnsupported(remoteAddress, H2C);
            case HTTP2_REQUIRED:
                return true;
            default:
                // Should never reach here.
                throw new Error();
        }
    }

    private void configureUpgradeCodec(Channel ch, Consumer<ChannelHandler> pipelineCustomizer) {
        final Http2ClientConnectionHandler http2Handler = newHttp2ConnectionHandler(ch, http2);
        // - h2c: HTTP/2 preface is always used.
        // - http: Either HTTP/1.1 upgrade or HTTP/2 preface is used depending on 'useHttp2Preface()'.
        final boolean shouldUsePreface = httpPreference == HttpPreference.HTTP2_REQUIRED ||
                                         clientFactory.useHttp2Preface();
        if (shouldUsePreface) {
            pipelineCustomizer.accept(new DowngradeHandler());
            pipelineCustomizer.accept(http2Handler);
        } else {
            final HttpClientCodec http1Codec = newHttp1Codec(
                    clientFactory.http1MaxInitialLineLength(),
                    clientFactory.http1MaxHeaderSize(),
                    clientFactory.http1MaxChunkSize());
            final Http2ClientUpgradeCodec http2ClientUpgradeCodec =
                    new Http2ClientUpgradeCodec(http2Handler);
            final HttpClientUpgradeHandler http2UpgradeHandler =
                    new HttpClientUpgradeHandler(
                            http1Codec, http2ClientUpgradeCodec,
                            (int) Math.min(Integer.MAX_VALUE, UPGRADE_RESPONSE_MAX_LENGTH));

            pipelineCustomizer.accept(http1Codec);
            pipelineCustomizer.accept(new WorkaroundHandler());
            pipelineCustomizer.accept(http2UpgradeHandler);
            pipelineCustomizer.accept(new UpgradeRequestHandler(http2Handler.responseDecoder()));
        }
    }

    // refer https://http2.github.io/http2-spec/#discover-http
    private void configureAsHttp(Channel ch, ChannelPromise connectionPromise) {
        final ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(TrafficLoggingHandler.CLIENT);

        if (attemptUpgrade()) {
            configureUpgradeCodec(ch, pipeline::addLast);
        } else {
            pipeline.addLast(newHttp1Codec(
                    clientFactory.http1MaxInitialLineLength(),
                    clientFactory.http1MaxHeaderSize(),
                    clientFactory.http1MaxChunkSize()));

            // NB: We do not call finishSuccessfully() immediately here
            //     because HttpSessionHandler is added when connectionPromise completes.
            connectionPromise.addListener(future -> {
                if (future.isSuccess()) {
                    finishSuccessfully(pipeline, H1C);
                }
            });
        }
    }

    // FIXME: Ensure unnecessary handlers are all removed from the pipeline for all protocol types.
    void finishSuccessfully(ChannelPipeline pipeline, SessionProtocol protocol) {

        if (protocol == H1 || protocol == H1C) {
            addBeforeSessionHandler(
                    pipeline, webSocket ? new WebSocketHttp1ClientChannelHandler(pipeline.channel())
                                        : new Http1ResponseDecoder(pipeline.channel(),
                                                                   clientFactory, protocol));
        } else if (protocol == H2 || protocol == H2C) {
            final int initialWindow = clientFactory.http2InitialConnectionWindowSize();
            if (initialWindow > DEFAULT_WINDOW_SIZE) {
                incrementLocalWindowSize(pipeline, initialWindow - DEFAULT_WINDOW_SIZE);
            }
        }

        pipeline.fireUserEventTriggered(protocol);
    }

    private static void incrementLocalWindowSize(ChannelPipeline pipeline, int delta) {
        try {
            final Http2Connection connection = pipeline.get(Http2ClientConnectionHandler.class).connection();
            connection.local().flowController().incrementWindowSize(connection.connectionStream(), delta);
        } catch (Http2Exception e) {
            logger.warn("Failed to increment local flowController window size: {}", delta, e);
        }
    }

    private static void addBeforeSessionHandler(ChannelPipeline pipeline, ChannelHandler handler) {
        final ChannelHandlerContext lastContext = pipeline.lastContext();
        if (lastContext.handler().getClass() == HttpSessionHandler.class) {
            // Get the name of the HttpSessionHandler so that we can put our handlers before it.
            pipeline.addBefore(lastContext.name(), null, handler);
        } else {
            // HttpSessionHandler was not added yet.
            pipeline.addLast(handler);
        }
    }

    void finishWithNegotiationFailure(
            ChannelHandlerContext ctx, SessionProtocol expected, SessionProtocol actual, String reason) {

        final ChannelPipeline pipeline = ctx.pipeline();
        pipeline.channel().eventLoop().execute(
                () -> pipeline.fireUserEventTriggered(
                        new SessionProtocolNegotiationException(expected, actual, reason)));
        ctx.close();
    }

    private boolean isHttps() {
        return sslCtx != null;
    }

    private static boolean isHttp2Protocol(SslHandler sslHandler) {
        return ApplicationProtocolNames.HTTP_2.equals(sslHandler.applicationProtocol());
    }

    /**
     * A handler that collects the ssl related metric.
     */
    private static final class ClientSslHandler extends SslHandler {
        private final ClientConnectionTimingsBuilder timingsBuilder;

        ClientSslHandler(SSLEngine engine, ClientConnectionTimingsBuilder timingsBuilder) {
            super(engine);
            this.timingsBuilder = timingsBuilder;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            timingsBuilder.tlsHandshakeStart();
            super.channelActive(ctx);
            handshakeFuture().addListener(future -> timingsBuilder.tlsHandshakeEnd());
        }
    }

    /**
     * A handler that triggers the cleartext upgrade to HTTP/2 by sending an initial HTTP request.
     */
    private final class UpgradeRequestHandler extends ChannelInboundHandlerAdapter {

        private final Http2ResponseDecoder responseDecoder;
        @Nullable
        private UpgradeEvent upgradeEvt;
        private String upgradeRejectionCause = "";
        private boolean needsToClose;

        UpgradeRequestHandler(Http2ResponseDecoder responseDecoder) {
            this.responseDecoder = responseDecoder;
        }

        /**
         * Sends the initial upgrade request, which is {@code "OPTIONS * HTTP/1.1"}.
         */
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            final FullHttpRequest upgradeReq =
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "*");

            // Note: There's no need to fill Connection, Upgrade, and HTTP2-Settings headers here
            //       because they are filled by Http2ClientUpgradeCodec.

            assert remoteAddress != null;

            final String host;
            if (remoteAddress instanceof InetSocketAddress) {
                final InetSocketAddress raddr = (InetSocketAddress) remoteAddress;
                host = ArmeriaHttpUtil.authorityHeader(
                        raddr.getHostString(), raddr.getPort(), (isHttps() ? H1 : H1C).defaultPort());
            } else {
                assert remoteAddress instanceof DomainSocketAddress : remoteAddress;
                host = SystemInfo.hostname();
            }

            upgradeReq.headers().set(HttpHeaderNames.HOST, host);
            upgradeReq.headers().set(HttpHeaderNames.USER_AGENT, UserAgentUtil.USER_AGENT);

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
            }, ctx.channel().eventLoop());

            responseDecoder.reserveUnfinishedResponse(Integer.MAX_VALUE);
            final DefaultClientRequestContext reqCtx = new DefaultClientRequestContext(
                    ctx.channel().eventLoop(), Flags.meterRegistry(), H1C, RequestId.random(),
                    com.linecorp.armeria.common.HttpMethod.OPTIONS,
                    RequestTarget.forClient("*"), ClientOptions.of(),
                    HttpRequest.of(com.linecorp.armeria.common.HttpMethod.OPTIONS, "*"),
                    null, REQUEST_OPTIONS_FOR_UPGRADE_REQUEST, CancellationScheduler.noop(),
                    System.nanoTime(), SystemInfo.currentTimeMicros());

            // NB: No need to set the response timeout because we have session creation timeout.
            responseDecoder.addResponse(0, res, reqCtx, ctx.channel().eventLoop());
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
            if (msg instanceof HttpResponse) {
                // The server rejected the upgrade request and sent its response in HTTP/1.
                assert upgradeEvt == UPGRADE_REJECTED;
                final HttpResponse res = (HttpResponse) msg;
                upgradeRejectionCause = "Upgrade request rejected with: " + res;
                // We can persist connection only when:
                // - The response has 'Connection: keep-alive' header on HTTP/1.0.
                // - The response has no 'Connection: close' header on HTTP/1.1.
                // and:
                // - The response has 'Content-Length' or 'Transfer-Encoding: chunked',
                //   i.e. possible to determine the end of the response.
                //
                // See: https://datatracker.ietf.org/doc/html/rfc2616#section-8.1.2.1
                //      https://datatracker.ietf.org/doc/html/rfc2616#section-4.4
                needsToClose = !(HttpUtil.isKeepAlive(res) &&
                                 (HttpUtil.isContentLengthSet(res) ||
                                  HttpUtil.isTransferEncodingChunked(res)));
                if (needsToClose) {
                    // No need to wait till the end of the response.
                    // Close the connection immediately and finish the upgrade process.
                    onUpgradeResponse(ctx, false);
                }

                ReferenceCountUtil.release(msg);
                return;
            }

            // We're not going to reuse the connection,
            // so we just discard everything received.
            if (needsToClose) {
                ReferenceCountUtil.release(msg);
                return;
            }

            // We're not going to close but reuse the connection,
            // so we wait until the end of the rejecting response.
            if (msg instanceof HttpContent) {
                if (msg instanceof LastHttpContent) {
                    onUpgradeResponse(ctx, false);
                }
                ReferenceCountUtil.release(msg);
                return;
            }

            ctx.fireChannelRead(msg);
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
                SessionProtocolNegotiationCache.setUnsupported(remoteAddress(ctx), http2);

                if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                    finishWithNegotiationFailure(ctx, http2, http1, upgradeRejectionCause);
                } else {
                    // We can silently retry with H1C.
                    retryWith(ctx, http1);
                }
                return;
            }

            if (success) {
                finishSuccessfully(p, http2);
            } else {
                SessionProtocolNegotiationCache.setUnsupported(remoteAddress(ctx), http2);

                if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                    finishWithNegotiationFailure(ctx, http2, http1, upgradeRejectionCause);
                    return;
                }

                finishSuccessfully(p, http1);
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
                SessionProtocolNegotiationCache.setUnsupported(remoteAddress(ctx), http2);
                if (httpPreference == HttpPreference.HTTP2_REQUIRED) {
                    finishWithNegotiationFailure(
                            ctx, http2, http1,
                            "received a non-HTTP/2 response for the HTTP/2 connection preface");
                } else {
                    // We can silently retry with HTTP/1.
                    retryWith(ctx, http1);
                }

                // We are going to close the connection really soon, so we don't need the response.
                in.skipBytes(in.readableBytes());
            } else {
                // The server responded with a non-HTTP/1 response. Continue treating the connection as HTTP/2.
                finishSuccessfully(p, http2);
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
                    finishWithNegotiationFailure(ctx, http2, http1,
                                                 "too little data to determine the HTTP version");
                } else {
                    // We can silently retry with HTTP/1.
                    retryWith(ctx, http1);
                }
            }
        }
    }

    static void retryWith(ChannelHandlerContext ctx, SessionProtocol protocol) {
        HttpSession.get(ctx.channel()).retryWith(protocol);
        ctx.close();
    }

    private Http2ClientConnectionHandler newHttp2ConnectionHandler(Channel ch, SessionProtocol protocol) {
        final Http2Connection connection = new DefaultHttp2Connection(/* server */ false);
        final Http2ConnectionEncoder encoder = encoder(connection);
        final Http2ConnectionDecoder decoder = decoder(connection, encoder);

        final Http2ClientConnectionHandlerBuilder builder =
                new Http2ClientConnectionHandlerBuilder(ch, clientFactory, protocol);
        return builder.codec(decoder, encoder)
                      .initialSettings(http2Settings())
                      .build();
    }

    private static Http2ConnectionEncoder encoder(Http2Connection connection) {
        Http2FrameWriter writer = new DefaultHttp2FrameWriter();
        writer = new Http2OutboundFrameLogger(writer, frameLogger);
        return new DefaultHttp2ConnectionEncoder(connection, writer);
    }

    private Http2ConnectionDecoder decoder(Http2Connection connection, Http2ConnectionEncoder encoder) {
        final ArmeriaHttp2HeadersDecoder headersDecoder = new ArmeriaHttp2HeadersDecoder(
                /* validateHeaders */ false, clientFactory.http2MaxHeaderListSize());
        Http2FrameReader reader = new DefaultHttp2FrameReader(headersDecoder);
        reader = new Http2InboundFrameLogger(reader, frameLogger);
        return new DefaultHttp2ConnectionDecoder(connection, encoder, reader);
    }

    private Http2Settings http2Settings() {
        final Http2Settings settings = new Http2Settings();
        final int initialWindowSize = clientFactory.http2InitialStreamWindowSize();
        if (initialWindowSize != DEFAULT_WINDOW_SIZE) {
            settings.initialWindowSize(initialWindowSize);
        }
        final int maxFrameSize = clientFactory.http2MaxFrameSize();
        if (maxFrameSize != DEFAULT_MAX_FRAME_SIZE) {
            settings.maxFrameSize(maxFrameSize);
        }
        settings.maxHeaderListSize(clientFactory.http2MaxHeaderListSize());
        settings.pushEnabled(false);
        return settings;
    }

    private static HttpClientCodec newHttp1Codec(
            int defaultMaxInitialLineLength, int defaultMaxHeaderSize, int defaultMaxChunkSize) {
        return new HttpClientCodec(defaultMaxInitialLineLength, defaultMaxHeaderSize, defaultMaxChunkSize);
    }

    /**
     * Suppresses unnecessary read calls and deactivates the {@link HttpSession} associated with a channel when
     * it is closed to ensure it isn't used anymore.
     */
    private static final class ReadSuppressingAndChannelDeactivatingHandler extends ReadSuppressingHandler {

        private static final ReadSuppressingAndChannelDeactivatingHandler
                INSTANCE = new ReadSuppressingAndChannelDeactivatingHandler();

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            HttpSession.get(ctx.channel()).markUnacquirable();
            super.close(ctx, promise);
        }
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
