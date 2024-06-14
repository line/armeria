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
import static com.linecorp.armeria.internal.client.ClosedStreamExceptionUtil.newClosedSessionException;
import static com.linecorp.armeria.internal.client.PendingExceptionUtil.getPendingException;
import static com.linecorp.armeria.internal.client.PendingExceptionUtil.setPendingException;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.HttpChannelPool.PoolKey;
import com.linecorp.armeria.client.proxy.ProxyType;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.client.PooledChannel;
import com.linecorp.armeria.internal.common.Http2GoAwayHandler;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.RequestContextUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.ProxyConnectionEvent;
import io.netty.handler.ssl.SslCompletionEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

final class HttpSessionHandler extends ChannelDuplexHandler implements HttpSession {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionHandler.class);

    private final HttpChannelPool channelPool;
    private final Channel channel;
    private final SocketAddress remoteAddress;
    private final Promise<Channel> sessionPromise;
    private final int connectionTimeoutMillis;
    private final SessionProtocol desiredProtocol;
    private final SerializationFormat serializationFormat;
    private final PoolKey poolKey;
    private final HttpClientFactory clientFactory;

    @Nullable
    private ScheduledFuture<?> sessionTimeoutFuture;
    @Nullable
    private SocketAddress proxyDestinationAddress;

    /**
     * Whether a new request can acquire this channel from {@link HttpChannelPool}.
     */
    @Nullable
    private volatile Boolean isAcquirable;

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
    @Nullable
    private SessionProtocol retryProtocol;

    /**
     * {@code true} if an {@link Http2Settings} has been received from the remote endpoint.
     */
    private boolean isSettingsFrameReceived;
    // Note: This field never becomes false once it becomes true.
    private boolean channelActivated;

    HttpSessionHandler(HttpChannelPool channelPool, Channel channel,
                       Promise<Channel> sessionPromise, int connectionTimeoutMillis,
                       SessionProtocol desiredProtocol, SerializationFormat serializationFormat,
                       PoolKey poolKey, HttpClientFactory clientFactory) {
        this.channelPool = requireNonNull(channelPool, "channelPool");
        this.channel = requireNonNull(channel, "channel");
        remoteAddress = channel.remoteAddress();
        this.sessionPromise = requireNonNull(sessionPromise, "sessionPromise");
        this.connectionTimeoutMillis = connectionTimeoutMillis;
        this.desiredProtocol = desiredProtocol;
        this.serializationFormat = serializationFormat;
        this.poolKey = poolKey;
        this.clientFactory = clientFactory;

        if (!poolKey.proxyConfig.proxyType().isForwardProxy()) {
            scheduleSessionTimeout(channel, sessionPromise, connectionTimeoutMillis, desiredProtocol);
        } else {
            // A session timeout for a proxied connection may be scheduled when ProxyConnectionEvent is
            // received.
        }
    }

    private void scheduleSessionTimeout(Channel channel, Promise<Channel> sessionPromise,
                                        int connectionTimeoutMillis, SessionProtocol desiredProtocol) {
        assert sessionTimeoutFuture == null : "sessionTimeoutFuture is scheduled already.";
        sessionTimeoutFuture = channel.eventLoop().schedule(() -> {
            if (sessionPromise.tryFailure(new SessionProtocolNegotiationException(
                    desiredProtocol,
                    "connection established, but session creation timed out: " + channel))) {
                channel.close();
            }
        }, connectionTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public SerializationFormat serializationFormat() {
        return serializationFormat;
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
        if (!channel.isActive()) {
            return false;
        }

        if (responseDecoder instanceof Http2ResponseDecoder) {
            // New requests that have already acquired this session can be sent over this session before a
            // GOAWAY is sent or received.
            final Http2GoAwayHandler goAwayHandler = ((Http2ResponseDecoder) responseDecoder).goAwayHandler();
            return !goAwayHandler.sentGoAway() && !goAwayHandler.receivedGoAway();
        } else {
            // Don't allow to send a request if a connection is closed or about to be closed for HTTP/1.
            return isAcquirable(responseDecoder.keepAliveHandler());
        }
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
        if (!protocol.isMultiplex() && !serializationFormat.requiresNewConnection(protocol)) {
            // When HTTP/1.1 is used and the serialization format does not require
            // a new connection (w.g. WebSocket):
            // If pipelining is enabled, return as soon as the request is fully sent.
            // If pipelining is disabled,
            // return after the response is fully received and the request is fully sent.
            final boolean useHttp1Pipelining = clientFactory.useHttp1Pipelining();
            final CompletableFuture<Void> completionFuture =
                    useHttp1Pipelining ? req.whenComplete()
                                       : CompletableFuture.allOf(req.whenComplete(), res.whenComplete());
            completionFuture.handle((ret, cause) -> {
                if (isAcquirable(responseDecoder.keepAliveHandler())) {
                    pooledChannel.release();
                }
                return null;
            });
        }

        try (SafeCloseable ignored = ctx.push()) {
            if (!ctx.exchangeType().isRequestStreaming()) {
                final AggregatedHttpRequestHandler reqHandler = new AggregatedHttpRequestHandler(
                        channel, requestEncoder, responseDecoder, req, res, ctx, writeTimeoutMillis);
                req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), channel.eventLoop()))
                   .handle(reqHandler);
                return;
            }

            final AbstractHttpRequestSubscriber subscriber = AbstractHttpRequestSubscriber.of(
                    channel, requestEncoder, responseDecoder, protocol,
                    ctx, req, res, writeTimeoutMillis, isWebSocket());
            req.subscribe(subscriber, channel.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);
        }
    }

    private boolean isWebSocket() {
        return serializationFormat == SerializationFormat.WS;
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

        assert responseDecoder != null;
        responseDecoder.decrementUnfinishedResponses();

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
    public void retryWith(SessionProtocol protocol) {
        retryProtocol = protocol;
    }

    @Override
    public boolean isAcquirable() {
        // responseDecoder and keepAliveHandler are set before this session is added to the pool.
        assert responseDecoder != null;
        return isAcquirable(responseDecoder.keepAliveHandler());
    }

    @Override
    public boolean isAcquirable(KeepAliveHandler keepAliveHandler) {
        final Boolean isAcquirable = this.isAcquirable;
        if (isAcquirable == null || !isAcquirable) {
            return false;
        }
        return !keepAliveHandler.needsDisconnection();
    }

    @Override
    public void markUnacquirable() {
        isAcquirable = false;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        channelActivated = channel.isActive();
        if (isAcquirable == null) {
            isAcquirable = channelActivated;
        }
        tryCompleteSessionPromise(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        channelActivated = true;
        // deactivate() may be called before channelActive() event if the first request contains
        // "connection:close" or triggers initiateConnectionShutdown().
        if (isAcquirable == null) {
            isAcquirable = true;
        }
        tryCompleteSessionPromise(ctx);
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
            isSettingsFrameReceived = true;
            tryCompleteSessionPromise(ctx);
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

            // Set the current protocol and its associated WaitsHolder implementation.
            final SessionProtocol protocol = (SessionProtocol) evt;
            this.protocol = protocol;
            if (protocol == H1 || protocol == H1C) {
                final HttpResponseDecoder responseDecoder;
                if (isWebSocket()) {
                    responseDecoder = ctx.pipeline().get(WebSocketHttp1ClientChannelHandler.class);
                } else {
                    responseDecoder = ctx.pipeline().get(Http1ResponseDecoder.class);
                }
                final KeepAliveHandler keepAliveHandler = responseDecoder.keepAliveHandler();
                keepAliveHandler.initialize(ctx);

                final ClientHttp1ObjectEncoder requestEncoder =
                        new ClientHttp1ObjectEncoder(channel, protocol, clientFactory.http1HeaderNaming(),
                                                     keepAliveHandler,
                                                     isWebSocket());
                if (keepAliveHandler instanceof Http1ClientKeepAliveHandler) {
                    ((Http1ClientKeepAliveHandler) keepAliveHandler).setEncoder(requestEncoder);
                }

                this.requestEncoder = requestEncoder;
                this.responseDecoder = responseDecoder;
            } else if (protocol == H2 || protocol == H2C) {
                final ChannelHandlerContext connectionHandlerCtx =
                        ctx.pipeline().context(Http2ClientConnectionHandler.class);
                final Http2ClientConnectionHandler connectionHandler =
                        (Http2ClientConnectionHandler) connectionHandlerCtx.handler();
                requestEncoder = new ClientHttp2ObjectEncoder(connectionHandlerCtx,
                                                              connectionHandler, protocol);
                responseDecoder = connectionHandler.responseDecoder();
            } else {
                throw new Error(); // Should never reach here.
            }

            tryCompleteSessionPromise(ctx);
            return;
        }

        if (evt instanceof SessionProtocolNegotiationException ||
            evt instanceof ProxyConnectException) {
            tryFailSessionPromise((Throwable) evt);
            ctx.close();
            return;
        }

        if (evt instanceof SslCompletionEvent) {
            final SslCompletionEvent sslCompletionEvent = (SslCompletionEvent) evt;
            if (sslCompletionEvent.isSuccess()) {
                // Expected event
            } else {
                Throwable handshakeException = sslCompletionEvent.cause();
                final Throwable pendingException = getPendingException(ctx);
                if (pendingException != null && handshakeException != pendingException) {
                    // Use pendingException as the primary cause.
                    pendingException.addSuppressed(handshakeException);
                    handshakeException = pendingException;
                }
                tryFailSessionPromise(handshakeException);
                ctx.close();
            }
            return;
        }

        if (evt instanceof Http2ConnectionPrefaceAndSettingsFrameWrittenEvent ||
            evt instanceof ChannelInputShutdownReadComplete) {
            // Expected events
            return;
        }

        if (evt instanceof ProxyConnectionEvent) {
            proxyDestinationAddress = ((ProxyConnectionEvent) evt).destinationAddress();
            if (!tryCompleteSessionPromise(ctx)) {
                // A session has not been created yet. Additional handshakes will be done by HTTP/1 or HTTP/2.
                if (poolKey.proxyConfig.proxyType().isForwardProxy()) {
                    scheduleSessionTimeout(channel, sessionPromise, connectionTimeoutMillis, desiredProtocol);
                }
            }
            return;
        }

        logger.warn("{} Unexpected user event: {}", channel, evt);
    }

    /**
     * Tries to complete the {@link #sessionPromise} if the session is ready to serve.
     *
     * @return {@code true} if the {@link #sessionPromise} has been completed successfully or exceptionally.
     *         {@code false} if the {@link #sessionPromise} is still incomplete.
     */
    private boolean tryCompleteSessionPromise(ChannelHandlerContext ctx) {
        if (protocol == null || !channelActivated) {
            return false;
        }
        if (poolKey.proxyConfig.proxyType() != ProxyType.DIRECT && proxyDestinationAddress == null) {
            // ProxyConnectionEvent is necessary for a proxied connection.
            return false;
        }
        if (protocol.isExplicitHttp2() && !isSettingsFrameReceived) {
            // Http2Settings should be received for HTTP/2.
            return false;
        }

        if (sessionTimeoutFuture != null) {
            sessionTimeoutFuture.cancel(false);
        }
        if (sessionPromise.trySuccess(channel) || sessionPromise.isSuccess()) {
            // The session is created successfully or has already been created.
        } else {
            // The session creation has been failed already; close the connection.
            ctx.close();
        }
        return true;
    }

    private void tryFailSessionPromise(Throwable cause) {
        if (sessionTimeoutFuture != null) {
            sessionTimeoutFuture.cancel(false);
        }
        if (!sessionPromise.isDone()) {
            sessionPromise.tryFailure(cause);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        isAcquirable = false;

        // Protocol upgrade has failed, but needs to retry.
        if (retryProtocol != null) {
            assert responseDecoder == null || !responseDecoder.hasUnfinishedResponses();
            if (sessionTimeoutFuture != null) {
                sessionTimeoutFuture.cancel(false);
            }
            if (proxyDestinationAddress != null) {
                channelPool.connect(proxyDestinationAddress, retryProtocol, serializationFormat,
                                    poolKey, sessionPromise, null);
            } else {
                channelPool.connect(remoteAddress, retryProtocol, serializationFormat, poolKey, sessionPromise,
                                    null);
            }
        } else {
            // Fail all pending responses.
            final HttpResponseDecoder responseDecoder = this.responseDecoder;
            final Throwable pendingException;
            if (responseDecoder != null && responseDecoder.hasUnfinishedResponses()) {
                pendingException = newClosedSessionException(ctx);
                responseDecoder.failUnfinishedResponses(pendingException);
            } else {
                pendingException = null;
            }

            // Cancel the timeout and reject the sessionPromise just in case the connection has been closed
            // even before the session protocol negotiation is done.
            tryFailSessionPromise(pendingException != null ? pendingException
                                                           : newClosedSessionException(ctx));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof ProxyConnectException) {
            final SessionProtocol protocol = this.protocol != null ? this.protocol : desiredProtocol;
            final UnprocessedRequestException wrapped = UnprocessedRequestException.of(cause);
            channelPool.maybeHandleProxyFailure(protocol, poolKey, wrapped);
            tryFailSessionPromise(wrapped);
            return;
        }
        setPendingException(ctx, new ClosedSessionException(cause));
        if (!(cause instanceof IOException)) {
            ctx.close();
        } else {
            // Netty will close the connection automatically on an IOException.
        }
    }
}
