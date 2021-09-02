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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.ProtocolViolationException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

final class Http1ResponseDecoder extends HttpResponseDecoder implements ChannelInboundHandler {

    private static final Logger logger = LoggerFactory.getLogger(Http1ResponseDecoder.class);

    private enum State {
        NEED_HEADERS,
        NEED_INFORMATIONAL_DATA,
        NEED_DATA_OR_TRAILERS,
        DISCARD
    }

    /** The response being decoded currently. */
    @Nullable
    private HttpResponseWrapper res;
    private KeepAliveHandler keepAliveHandler = NoopKeepAliveHandler.INSTANCE;
    private int resId = 1;
    private int lastPingReqId = -1;
    private State state = State.NEED_HEADERS;

    Http1ResponseDecoder(Channel channel) {
        super(channel, InboundTrafficController.ofHttp1(channel));
    }

    @Override
    HttpResponseWrapper addResponse(
            int id, DecodedHttpResponse res, @Nullable ClientRequestContext ctx, EventLoop eventLoop,
            long responseTimeoutMillis, long maxContentLength) {

        final HttpResponseWrapper resWrapper =
                super.addResponse(id, res, ctx, eventLoop, responseTimeoutMillis, maxContentLength);

        resWrapper.whenComplete().handle((unused, cause) -> {
            if (eventLoop.inEventLoop()) {
                onWrapperCompleted(resWrapper, cause);
            } else {
                eventLoop.execute(() -> onWrapperCompleted(resWrapper, cause));
            }
            return null;
        });

        return resWrapper;
    }

    private void onWrapperCompleted(HttpResponseWrapper resWrapper, @Nullable Throwable cause) {
        // Cancel timeout future and abort the request if it exists.
        resWrapper.onSubscriptionCancelled(cause);

        if (cause != null) {
            // Disconnect when the response has been closed with an exception because there's no way
            // to recover from it in HTTP/1.
            channel().close();
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        destroyKeepAliveHandler();
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);
        ctx.fireChannelRegistered();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelUnregistered();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        maybeInitializeKeepAliveHandler(ctx);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (res != null) {
            res.close(ClosedSessionException.get());
        }
        destroyKeepAliveHandler();
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpObject)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (isPing()) {
            onPingRead(msg);
            ReferenceCountUtil.release(msg);
            return;
        }

        try {
            switch (state) {
                case NEED_HEADERS:
                    if (msg instanceof HttpResponse) {
                        final HttpResponse nettyRes = (HttpResponse) msg;
                        final DecoderResult decoderResult = nettyRes.decoderResult();
                        if (!decoderResult.isSuccess()) {
                            fail(ctx, new ProtocolViolationException(decoderResult.cause()));
                            return;
                        }

                        if (!HttpUtil.isKeepAlive(nettyRes)) {
                            disconnectWhenFinished();
                        }

                        @Nullable
                        final HttpResponseWrapper res = getResponse(resId);
                        if (res == null && ArmeriaHttpUtil.isRequestTimeoutResponse(nettyRes)) {
                            close(ctx);
                            return;
                        }
                        assert res != null;
                        this.res = res;

                        res.logResponseFirstBytesTransferred();

                        if (nettyRes.status().codeClass() == HttpStatusClass.INFORMATIONAL) {
                            state = State.NEED_INFORMATIONAL_DATA;
                        } else {
                            state = State.NEED_DATA_OR_TRAILERS;
                        }

                        res.initTimeout();
                        if (!res.tryWrite(ArmeriaHttpUtil.toArmeria(nettyRes))) {
                            fail(ctx, ClosedStreamException.get());
                            return;
                        }
                    } else {
                        failWithUnexpectedMessageType(ctx, msg, HttpResponse.class);
                    }
                    break;
                case NEED_INFORMATIONAL_DATA:
                    if (msg instanceof LastHttpContent) {
                        state = State.NEED_HEADERS;
                    } else {
                        failWithUnexpectedMessageType(ctx, msg, LastHttpContent.class);
                    }
                    break;
                case NEED_DATA_OR_TRAILERS:
                    if (msg instanceof HttpContent) {
                        final HttpContent content = (HttpContent) msg;
                        final DecoderResult decoderResult = content.decoderResult();
                        if (!decoderResult.isSuccess()) {
                            fail(ctx, new ProtocolViolationException(decoderResult.cause()));
                            return;
                        }

                        final ByteBuf data = content.content();
                        final int dataLength = data.readableBytes();
                        if (dataLength > 0) {
                            assert res != null;
                            final long maxContentLength = res.maxContentLength();
                            final long writtenBytes = res.writtenBytes();
                            if (maxContentLength > 0 && writtenBytes > maxContentLength - dataLength) {
                                final long transferred = LongMath.saturatedAdd(writtenBytes, dataLength);
                                fail(ctx, ContentTooLargeException.builder()
                                                                  .maxContentLength(maxContentLength)
                                                                  .contentLength(res.headers())
                                                                  .transferred(transferred)
                                                                  .build());
                                return;
                            } else if (!res.tryWrite(HttpData.wrap(data.retain()))) {
                                fail(ctx, ClosedStreamException.get());
                                return;
                            }
                        }

                        if (msg instanceof LastHttpContent) {
                            final HttpResponseWrapper res = removeResponse(resId++);
                            assert res != null;
                            assert this.res == res;
                            this.res = null;

                            state = State.NEED_HEADERS;

                            final HttpHeaders trailingHeaders = ((LastHttpContent) msg).trailingHeaders();
                            if (!trailingHeaders.isEmpty() &&
                                !res.tryWrite(ArmeriaHttpUtil.toArmeria(trailingHeaders))) {
                                fail(ctx, ClosedStreamException.get());
                                return;
                            }

                            res.close();

                            if (needsToDisconnectNow()) {
                                ctx.close();
                            }
                        }
                    } else {
                        failWithUnexpectedMessageType(ctx, msg, HttpContent.class);
                    }
                    break;
                case DISCARD:
                    break;
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void failWithUnexpectedMessageType(ChannelHandlerContext ctx, Object msg, Class<?> expected) {
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append("unexpected message type: " + msg.getClass().getName() +
                       " (expected: " + expected.getName() + ", channel: " + ctx.channel() +
                       ", resId: " + resId);
            if (lastPingReqId == -1) {
                buf.append(')');
            } else {
                buf.append(", lastPingReqId: " + lastPingReqId + ')');
            }
            fail(ctx, new ProtocolViolationException(buf.toString()));
        }
    }

    private void fail(ChannelHandlerContext ctx, Throwable cause) {
        state = State.DISCARD;

        @Nullable
        final HttpResponseWrapper res = this.res;
        this.res = null;

        if (res != null) {
            res.close(cause);
        } else {
            logger.warn("Unexpected exception:", cause);
        }

        ctx.close();
    }

    private void close(ChannelHandlerContext ctx) {
        state = State.DISCARD;
        ctx.close();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelReadComplete();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.fireExceptionCaught(cause);
    }

    @Override
    KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }

    void setKeepAliveHandler(ChannelHandlerContext ctx, KeepAliveHandler keepAliveHandler) {
        assert keepAliveHandler instanceof Http1ClientKeepAliveHandler;
        this.keepAliveHandler = keepAliveHandler;
        maybeInitializeKeepAliveHandler(ctx);
    }

    private void maybeInitializeKeepAliveHandler(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            keepAliveHandler.initialize(ctx);
        }
    }

    private void destroyKeepAliveHandler() {
        keepAliveHandler.destroy();
    }

    private void onPingRead(Object msg) {
        if (msg instanceof HttpResponse) {
            assert keepAliveHandler != NoopKeepAliveHandler.INSTANCE;
            keepAliveHandler.onPing();
        }
        if (msg instanceof LastHttpContent) {
            onPingComplete();
        }
    }

    void setPingReqId(int id) {
        lastPingReqId = id;
    }

    boolean isPingReqId(int id) {
        return lastPingReqId == id;
    }

    private boolean isPing() {
        return lastPingReqId == resId;
    }

    private void onPingComplete() {
        lastPingReqId = -1;
        resId++;
    }
}
