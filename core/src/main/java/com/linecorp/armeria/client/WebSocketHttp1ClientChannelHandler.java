/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.armeria.client.AbstractHttpResponseDecoder.contentTooLargeException;
import static com.linecorp.armeria.internal.client.ClosedStreamExceptionUtil.newClosedSessionException;
import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ProtocolViolationException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.ReferenceCountUtil;

final class WebSocketHttp1ClientChannelHandler extends ChannelDuplexHandler implements HttpResponseDecoder {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHttp1ClientChannelHandler.class);

    private enum State {
        NEEDS_HANDSHAKE_RESPONSE,
        NEEDS_HANDSHAKE_RESPONSE_END,
        UPGRADE_COMPLETE
    }

    private final Channel channel;
    private final InboundTrafficController inboundTrafficController;
    @Nullable
    private HttpResponseWrapper res;
    private final KeepAliveHandler keepAliveHandler;

    private State state = State.NEEDS_HANDSHAKE_RESPONSE;
    @Nullable
    private HttpSession httpSession;

    WebSocketHttp1ClientChannelHandler(Channel channel) {
        this.channel = channel;
        inboundTrafficController = InboundTrafficController.ofHttp1(channel);

        // Use NoopKeepAliveHandler because
        // - hasRequestsInProgress is always true for WebSocket
        // - a Ping frame is not sent by the keepAliveHandler but by the upper layer.
        // TODO(minwoox): Provide a dedicated KeepAliveHandler to the upper layer (e.g. WebSocketClient)
        //                that handles Ping frames for WebSocket.
        keepAliveHandler = new NoopKeepAliveHandler();
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public InboundTrafficController inboundTrafficController() {
        return inboundTrafficController;
    }

    @Override
    public HttpResponseWrapper addResponse(int id, DecodedHttpResponse decodedHttpResponse,
                                           ClientRequestContext ctx, EventLoop eventLoop) {
        assert res == null;
        res = new WebSocketHttp1ResponseWrapper(decodedHttpResponse, eventLoop, ctx,
                                                ctx.responseTimeoutMillis(), ctx.maxResponseLength());
        return res;
    }

    @Nullable
    @Override
    public HttpResponseWrapper getResponse(int unused) {
        return res;
    }

    @Nullable
    @Override
    public HttpResponseWrapper removeResponse(int unused) {
        return res;
    }

    @Override
    public boolean hasUnfinishedResponses() {
        return res != null;
    }

    @Override
    public boolean reserveUnfinishedResponse(int unused) {
        return true;
    }

    @Override
    public void decrementUnfinishedResponses() {}

    @Override
    public void failUnfinishedResponses(Throwable cause) {
        if (res != null) {
            res.close(cause);
        }
    }

    @Override
    public HttpSession session() {
        if (httpSession != null) {
            return httpSession;
        }
        return httpSession = HttpSession.get(channel);
    }

    @Override
    public KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        keepAliveHandler.destroy();
        if (res != null) {
            res.close(newClosedSessionException(ctx));
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            switch (state) {
                case NEEDS_HANDSHAKE_RESPONSE:
                    if (!(msg instanceof HttpObject)) {
                        ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
                        return;
                    }
                    if (!(msg instanceof HttpResponse)) {
                        failWithUnexpectedMessageType(ctx, msg, HttpResponse.class);
                        return;
                    }

                    final HttpResponse nettyRes = (HttpResponse) msg;
                    final DecoderResult decoderResult = nettyRes.decoderResult();
                    if (!decoderResult.isSuccess()) {
                        fail(ctx, new ProtocolViolationException(decoderResult.cause()));
                        return;
                    }

                    if (!HttpUtil.isKeepAlive(nettyRes)) {
                        session().markUnacquirable();
                    }

                    if (res == null && ArmeriaHttpUtil.isRequestTimeoutResponse(nettyRes)) {
                        ctx.close();
                        return;
                    }

                    res.startResponse();
                    final ResponseHeaders responseHeaders = ArmeriaHttpUtil.toArmeria(nettyRes);
                    if (responseHeaders.status() == HttpStatus.SWITCHING_PROTOCOLS) {
                        state = State.NEEDS_HANDSHAKE_RESPONSE_END;
                    }
                    if (!res.tryWriteResponseHeaders(responseHeaders)) {
                        fail(ctx, newClosedSessionException(ctx));
                    }
                    break;
                case NEEDS_HANDSHAKE_RESPONSE_END:
                    // HttpClientCodec produces this after creating the headers. We can just ignore it.
                    if (msg != EMPTY_LAST_CONTENT) {
                        failWithUnexpectedMessageType(ctx, msg, EMPTY_LAST_CONTENT.getClass());
                        return;
                    }
                    // The state should be set to UPGRADE_COMPLETE before removing HttpClientCodec.
                    // Because pipeline.remove() could trigger channelRead() recursively.
                    state = State.UPGRADE_COMPLETE;
                    final ChannelPipeline pipeline = ctx.pipeline();
                    pipeline.remove(HttpClientCodec.class);
                    break;
                case UPGRADE_COMPLETE:
                    assert msg instanceof ByteBuf;
                    final ByteBuf data = (ByteBuf) msg;
                    final int dataLength = data.readableBytes();
                    if (dataLength > 0) {
                        final long maxContentLength = res.maxContentLength();
                        final long writtenBytes = res.writtenBytes();
                        if (maxContentLength > 0 && writtenBytes > maxContentLength - dataLength) {
                            final long transferred = LongMath.saturatedAdd(writtenBytes, dataLength);
                            res.close(contentTooLargeException(res, transferred));
                            ctx.close();
                            return;
                        }
                        if (!res.tryWriteData(HttpData.wrap(data.retain()))) {
                            ctx.close();
                        }
                    }
                    break;
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void failWithUnexpectedMessageType(ChannelHandlerContext ctx, Object msg, Class<?> expected) {
        final String message;
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append("unexpected message type: " + msg.getClass().getName() +
                       " (expected: " + expected.getName() + ", channel: " + ctx.channel() + ')');
            message = buf.toString();
        }
        fail(ctx, new ProtocolViolationException(message));
    }

    private void fail(ChannelHandlerContext ctx, Throwable cause) {
        if (res != null) {
            res.close(cause);
        } else {
            logger.warn("Unexpected exception:", cause);
        }

        ctx.close();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpContent) {
            ctx.write(((HttpContent) msg).content(), promise);
            return;
        }
        ctx.write(msg, promise);
    }
}
