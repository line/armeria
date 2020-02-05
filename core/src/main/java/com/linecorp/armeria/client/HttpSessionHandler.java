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
import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static java.util.Objects.requireNonNull;

import java.net.SocketAddress;
import java.util.concurrent.ScheduledFuture;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.Http2ObjectEncoder;
import com.linecorp.armeria.internal.common.HttpObjectEncoder;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.RequestContextUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2ConnectionPrefaceAndSettingsFrameWrittenEvent;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

final class HttpSessionHandler extends ChannelDuplexHandler implements HttpSession {

    private static final Logger logger = LoggerFactory.getLogger(HttpSessionHandler.class);

    /**
     * 2^29 - We could have used 2^30 but this should be large enough.
     */
    private static final int MAX_NUM_REQUESTS_SENT = 536870912;

    static final AttributeKey<Throwable> PENDING_EXCEPTION =
            AttributeKey.valueOf(HttpSessionHandler.class, "PENDING_EXCEPTION");

    private final HttpChannelPool channelPool;
    private final Channel channel;
    private final Promise<Channel> sessionPromise;
    private final ScheduledFuture<?> sessionTimeoutFuture;
    private final SocketAddress remoteAddress;

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
    private HttpObjectEncoder requestEncoder;

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
                       Promise<Channel> sessionPromise, ScheduledFuture<?> sessionTimeoutFuture) {

        this.channelPool = requireNonNull(channelPool, "channelPool");
        this.channel = requireNonNull(channel, "channel");
        final SocketAddress remoteAddress = channel.remoteAddress();
        this.remoteAddress = requireNonNull(remoteAddress, "remoteAddress");
        this.sessionPromise = requireNonNull(sessionPromise, "sessionPromise");
        this.sessionTimeoutFuture = requireNonNull(sessionTimeoutFuture, "sessionTimeoutFuture");
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
    public int unfinishedResponses() {
        assert responseDecoder != null;
        return responseDecoder.unfinishedResponses();
    }

    @Override
    public int maxUnfinishedResponses() {
        return maxUnfinishedResponses;
    }

    @Override
    public boolean canSendRequest() {
        assert responseDecoder != null;
        return active && !responseDecoder.needsToDisconnectWhenFinished();
    }

    @Override
    public boolean invoke(ClientRequestContext ctx, HttpRequest req, DecodedHttpResponse res) {
        if (handleEarlyCancellation(ctx, req, res)) {
            return true;
        }

        final long writeTimeoutMillis = ctx.writeTimeoutMillis();
        final long responseTimeoutMillis = ctx.responseTimeoutMillis();
        final long maxContentLength = ctx.maxResponseLength();

        assert responseDecoder != null;
        assert requestEncoder != null;

        final int numRequestsSent = ++this.numRequestsSent;
        final HttpResponseWrapper wrappedRes =
                responseDecoder.addResponse(numRequestsSent, res, ctx,
                                            channel.eventLoop(), responseTimeoutMillis, maxContentLength);
        if (ctx instanceof DefaultClientRequestContext) {
            ((DefaultClientRequestContext) ctx).setResponseTimeoutController(wrappedRes);
        }

        final HttpRequestSubscriber reqSubscriber =
                new HttpRequestSubscriber(channel, remoteAddress, requestEncoder, numRequestsSent,
                                          req, wrappedRes, ctx, writeTimeoutMillis);
        req.subscribe(reqSubscriber, channel.eventLoop(), WITH_POOLED_OBJECTS);

        if (numRequestsSent >= MAX_NUM_REQUESTS_SENT) {
            responseDecoder.disconnectWhenFinished();
            return false;
        } else {
            return true;
        }
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
            throw new IllegalStateException("unexpected message type: " + typeInfo);
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
                requestEncoder = new Http1ObjectEncoder(channel, false, protocol.isTls());
                responseDecoder = ctx.pipeline().get(Http1ResponseDecoder.class);
            } else if (protocol == H2 || protocol == H2C) {
                final Http2ConnectionHandler handler = ctx.pipeline().get(Http2ConnectionHandler.class);
                requestEncoder = new Http2ObjectEncoder(ctx, handler.encoder());
                responseDecoder = ctx.pipeline().get(Http2ClientConnectionHandler.class).responseDecoder();
            } else {
                throw new Error(); // Should never reach here.
            }

            if (!sessionPromise.trySuccess(channel)) {
                // Session creation has been failed already; close the connection.
                ctx.close();
            }
            return;
        }

        if (evt instanceof SessionProtocolNegotiationException) {
            sessionTimeoutFuture.cancel(false);
            sessionPromise.tryFailure((SessionProtocolNegotiationException) evt);
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

        logger.warn("{} Unexpected user event: {}", channel, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        active = false;

        // Protocol upgrade has failed, but needs to retry.
        if (needsRetryWithH1C) {
            assert responseDecoder == null || !responseDecoder.hasUnfinishedResponses();
            sessionTimeoutFuture.cancel(false);
            channelPool.connect(channel.remoteAddress(), H1C, sessionPromise);
        } else {
            // Fail all pending responses.
            final Throwable throwable;
            if (ctx.channel().hasAttr(PENDING_EXCEPTION)) {
                throwable = ctx.channel().attr(PENDING_EXCEPTION).get();
            } else {
                throwable = ClosedSessionException.get();
            }
            failUnfinishedResponses(throwable);
            // Cancel the timeout and reject the sessionPromise just in case the connection has been closed
            // even before the session protocol negotiation is done.
            sessionTimeoutFuture.cancel(false);
            sessionPromise.tryFailure(throwable);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Exceptions.logIfUnexpected(logger, channel, protocol(), cause);
        if (channel.isActive()) {
            ctx.close();
        }
    }

    private void failUnfinishedResponses(Throwable e) {
        final HttpResponseDecoder responseDecoder = this.responseDecoder;
        if (responseDecoder == null) {
            return;
        }

        responseDecoder.failUnfinishedResponses(e);
    }
}
