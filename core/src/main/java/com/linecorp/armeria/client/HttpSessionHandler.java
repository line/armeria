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
import static com.linecorp.armeria.internal.common.KeepAliveHandlerUtil.needKeepAliveHandler;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.HttpChannelPool.PoolKey;
import com.linecorp.armeria.client.proxy.ProxyType;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.RequestContextUtil;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.ProxyConnectionEvent;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

final class HttpSessionHandler extends ChannelDuplexHandler implements HttpSession {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionHandler.class);

    private static final AttributeKey<Throwable> PENDING_EXCEPTION =
            AttributeKey.valueOf(HttpSessionHandler.class, "PENDING_EXCEPTION");

    private final HttpChannelPool channelPool;
    private final Channel channel;
    private final SocketAddress remoteAddress;
    private final Promise<Channel> sessionPromise;
    private final ScheduledFuture<?> sessionTimeoutFuture;
    private final SessionProtocol desiredProtocol;
    private final PoolKey poolKey;
    private final HttpClientFactory clientFactory;

    @Nullable
    private SocketAddress proxyDestinationAddress;

    /**
     * Whether the current channel is active or not.
     */
    private volatile boolean active;

    /**
     * The current negotiated {@link SessionProtocol}.
     */
    @Nullable
    private SessionProtocol protocol;

    @Nullable
    private HttpResponseDecoder responseDecoder;
    @Nullable
    private ClientHttpObjectEncoder requestEncoder;

    /**
     * The maximum number of unfinished requests. In HTTP/2, this value is identical to MAX_CONCURRENT_STREAMS.
     * In HTTP/1, this value stays at {@link Integer#MAX_VALUE}.
     */
    private int maxUnfinishedResponses = Integer.MAX_VALUE;

    /**
     * The number of requests sent. Disconnects when it reaches at {@link #MAX_NUM_REQUESTS_SENT}.
     */
    private int numRequestsSent;

    /**
     * {@code true} if the protocol upgrade to HTTP/2 has failed.
     * If set to {@code true}, another connection attempt will follow.
     */
    private boolean needsRetryWithH1C;

    HttpSessionHandler(HttpChannelPool channelPool, Channel channel,
                       Promise<Channel> sessionPromise, ScheduledFuture<?> sessionTimeoutFuture,
                       SessionProtocol desiredProtocol, PoolKey poolKey,
                       HttpClientFactory clientFactory) {
        this.channelPool = requireNonNull(channelPool, "channelPool");
        this.channel = requireNonNull(channel, "channel");
        remoteAddress = channel.remoteAddress();
        this.sessionPromise = requireNonNull(sessionPromise, "sessionPromise");
        this.sessionTimeoutFuture = requireNonNull(sessionTimeoutFuture, "sessionTimeoutFuture");
        this.desiredProtocol = desiredProtocol;
        this.poolKey = poolKey;
        this.clientFactory = clientFactory;
    }

    @Override
    public SessionProtocol protocol() {
        return protocol;
    }

    @Override
    public InboundTrafficController inboundTrafficController() {
        assert responseDecoder != null;
        return responseDecoder.inboundTrafficController();
    }

    @Override
    public boolean hasUnfinishedResponses() {
        // This method can be called from KeepAliveHandler before HTTP/2 connection receives
        // a settings frame which triggers to initialize responseDecoder.
        // So we just return false because it does not have any unfinished responses.
        if (responseDecoder == null) {
            return false;
        }
        return responseDecoder.hasUnfinishedResponses();
    }

    @Override
    public boolean incrementNumUnfinishedResponses() {
        assert responseDecoder != null;
        return responseDecoder.reserveUnfinishedResponse(maxUnfinishedResponses);
    }

    @Override
    public boolean canSendRequest() {
        assert responseDecoder != null;
        return active && !responseDecoder.needsToDisconnectWhenFinished();
    }

    @Override
    public void invoke(PooledChannel pooledChannel, ClientRequestContext ctx,
                       HttpRequest req, DecodedHttpResponse res) {
        if (handleEarlyCancellation(ctx, req, res)) {
            pooledChannel.release();
            return;
        }

        final long writeTimeoutMillis = ctx.writeTimeoutMillis();

        assert protocol != null;
        assert responseDecoder != null;
        assert requestEncoder != null;
        if (!protocol.isMultiplex()) {
            // When HTTP/1.1 is used:
            // If pipelining is enabled, return as soon as the request is fully sent.
            // If pipelining is disabled,
            // return after the response is fully received and the request is fully sent.
            final boolean useHttp1Pipelining = clientFactory.useHttp1Pipelining();
            final CompletableFuture<Void> completionFuture =
                    useHttp1Pipelining ? req.whenComplete()
                                       : CompletableFuture.allOf(req.whenComplete(), res.whenComplete());
            completionFuture.handle((ret, cause) -> {
                if (!responseDecoder.needsToDisconnectWhenFinished()) {
                    pooledChannel.release();
                }
                return null;
            });
        }

        final HttpRequestSubscriber reqSubscriber =
                new HttpRequestSubscriber(channel, requestEncoder, responseDecoder,
                                          req, res, ctx, writeTimeoutMillis);
        req.subscribe(reqSubscriber, channel.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);
    }

    @Override
    public int incrementAndGetNumRequestsSent() {
        return ++numRequestsSent;
    }

    private boolean handleEarlyCancellation(ClientRequestContext ctx, HttpRequest req,
                                            DecodedHttpResponse res) {
        if (res.isOpen()) {
            return false;
        }

        // The response has been closed even before its request is sent.
        assert protocol != null;

        try (SafeCloseable ignored = RequestContextUtil.pop()) {
            req.abort(CancelledSubscriptionException.get());
            ctx.logBuilder().session(channel, protocol, null);
            ctx.logBuilder().requestHeaders(req.headers());
            req.whenComplete().handle((unused, cause) -> {
                if (cause == null) {
                    ctx.logBuilder().endRequest();
                } else {
                    ctx.logBuilder().endRequest(cause);
                }
                return null;
            });
            res.whenComplete().handle((unused, cause) -> {
                if (cause == null) {
                    ctx.logBuilder().endResponse();
                } else {
                    ctx.logBuilder().endResponse(cause);
                }
                return null;
            });
        }

        return true;
    }

    @Override
    public void retryWithH1C() {
        needsRetryWithH1C = true;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void deactivate() {
        active = false;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        active = channel.isActive();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        active = true;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2Settings) {
            final Long maxConcurrentStreams = ((Http2Settings) msg).maxConcurrentStreams();
            if (maxConcurrentStreams != null) {
                maxUnfinishedResponses =
                        maxConcurrentStreams > Integer.MAX_VALUE ? Integer.MAX_VALUE
                                                                 : maxConcurrentStreams.intValue();
            } else {
                maxUnfinishedResponses = Integer.MAX_VALUE;
            }
            return;
        }

        // Handle an unexpected message by raising an exception with debugging information.
        try {
            final String typeInfo;
            if (msg instanceof ByteBuf) {
                typeInfo = msg + " HexDump: " + ByteBufUtil.hexDump((ByteBuf) msg);
            } else {
                typeInfo = String.valueOf(msg);
            }
            throw new IllegalStateException("unexpected message type: " + typeInfo + " (expected: ByteBuf)");
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SessionProtocol) {
            assert protocol == null;
            assert responseDecoder == null;

            sessionTimeoutFuture.cancel(false);

            // Set the current protocol and its associated WaitsHolder implementation.
            final SessionProtocol protocol = (SessionProtocol) evt;
            this.protocol = protocol;
            if (protocol == H1 || protocol == H1C) {
                final ClientHttp1ObjectEncoder requestEncoder =
                        new ClientHttp1ObjectEncoder(channel, protocol, clientFactory.http1HeaderNaming());
                final Http1ResponseDecoder responseDecoder = ctx.pipeline().get(Http1ResponseDecoder.class);

                final long idleTimeoutMillis = clientFactory.idleTimeoutMillis();
                final long pingIntervalMillis = clientFactory.pingIntervalMillis();
                final long maxConnectionAgeMillis = clientFactory.maxConnectionAgeMillis();
                final int maxNumRequests = clientFactory.maxNumRequests();
                final boolean needKeepAliveHandler =
                        needKeepAliveHandler(idleTimeoutMillis, pingIntervalMillis,
                                             maxConnectionAgeMillis, maxNumRequests);
                if (needKeepAliveHandler) {
                    final Timer keepAliveTimer =
                            MoreMeters.newTimer(clientFactory.meterRegistry(),
                                                "armeria.client.connections.lifespan",
                                                ImmutableList.of(Tag.of("protocol", protocol.uriText())));
                    final Http1ClientKeepAliveHandler keepAliveHandler =
                            new Http1ClientKeepAliveHandler(
                                    channel, requestEncoder, responseDecoder,
                                    keepAliveTimer, idleTimeoutMillis, pingIntervalMillis,
                                    maxConnectionAgeMillis, maxNumRequests);
                    requestEncoder.setKeepAliveHandler(keepAliveHandler);
                    responseDecoder.setKeepAliveHandler(ctx, keepAliveHandler);
                }

                this.requestEncoder = requestEncoder;
                this.responseDecoder = responseDecoder;
            } else if (protocol == H2 || protocol == H2C) {
                final Http2ConnectionHandler handler = ctx.pipeline().get(Http2ConnectionHandler.class);
                final Http2ClientConnectionHandler clientHandler =
                        ctx.pipeline().get(Http2ClientConnectionHandler.class);
                requestEncoder = new ClientHttp2ObjectEncoder(ctx, handler.encoder(),
                                                              protocol, clientHandler.keepAliveHandler());
                responseDecoder = clientHandler.responseDecoder();
            } else {
                throw new Error(); // Should never reach here.
            }

            if (poolKey.proxyConfig.proxyType() != ProxyType.DIRECT) {
                if (proxyDestinationAddress != null) {
                    // ProxyConnectionEvent was already triggered.
                    tryCompleteSessionPromise(ctx);
                }
            } else {
                tryCompleteSessionPromise(ctx);
            }
            return;
        }

        if (evt instanceof SessionProtocolNegotiationException ||
            evt instanceof ProxyConnectException) {
            sessionTimeoutFuture.cancel(false);
            sessionPromise.tryFailure((Throwable) evt);
            ctx.close();
            return;
        }

        if (evt instanceof Http2ConnectionPrefaceAndSettingsFrameWrittenEvent ||
            evt instanceof SslHandshakeCompletionEvent ||
            evt instanceof SslCloseCompletionEvent ||
            evt instanceof ChannelInputShutdownReadComplete) {
            // Expected events
            return;
        }

        if (evt instanceof ProxyConnectionEvent) {
            proxyDestinationAddress = ((ProxyConnectionEvent) evt).destinationAddress();
            if (protocol != null) {
                // SessionProtocol event was already triggered.
                tryCompleteSessionPromise(ctx);
            }
            return;
        }

        logger.warn("{} Unexpected user event: {}", channel, evt);
    }

    private void tryCompleteSessionPromise(ChannelHandlerContext ctx) {
        if (!sessionPromise.trySuccess(channel)) {
            // Session creation has been failed already; close the connection.
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        active = false;

        // Protocol upgrade has failed, but needs to retry.
        if (needsRetryWithH1C) {
            assert responseDecoder == null || !responseDecoder.hasUnfinishedResponses();
            sessionTimeoutFuture.cancel(false);
            if (proxyDestinationAddress != null) {
                channelPool.connect(proxyDestinationAddress, H1C, poolKey, sessionPromise);
            } else {
                channelPool.connect(remoteAddress, H1C, poolKey, sessionPromise);
            }
        } else {
            // Fail all pending responses.
            final HttpResponseDecoder responseDecoder = this.responseDecoder;
            final Throwable pendingException;
            if (responseDecoder != null && responseDecoder.hasUnfinishedResponses()) {
                pendingException = getPendingException(ctx);
                responseDecoder.failUnfinishedResponses(pendingException);
            } else {
                pendingException = null;
            }

            // Cancel the timeout and reject the sessionPromise just in case the connection has been closed
            // even before the session protocol negotiation is done.
            sessionTimeoutFuture.cancel(false);
            if (!sessionPromise.isDone()) {
                sessionPromise.tryFailure(pendingException != null ? pendingException
                                                                   : getPendingException(ctx));
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof ProxyConnectException) {
            final SessionProtocol protocol = this.protocol != null ? this.protocol : desiredProtocol;
            final UnprocessedRequestException wrapped = UnprocessedRequestException.of(cause);
            channelPool.invokeProxyConnectFailed(protocol, poolKey, wrapped);
            sessionPromise.tryFailure(wrapped);
            return;
        }
        setPendingException(ctx, new ClosedSessionException(cause));
        if (!(cause instanceof IOException)) {
            ctx.close();
        } else {
            // Netty will close the connection automatically on an IOException.
        }
    }

    private static Throwable getPendingException(ChannelHandlerContext ctx) {
        if (ctx.channel().hasAttr(PENDING_EXCEPTION)) {
            return ctx.channel().attr(PENDING_EXCEPTION).get();
        }

        return ClosedSessionException.get();
    }

    static void setPendingException(ChannelHandlerContext ctx, Throwable cause) {
        final Throwable previousCause = ctx.channel().attr(PENDING_EXCEPTION).setIfAbsent(cause);
        if (previousCause != null && logger.isWarnEnabled()) {
            logger.warn("{} Unexpected suppressed exception:", ctx.channel(), cause);
        }
    }
}
