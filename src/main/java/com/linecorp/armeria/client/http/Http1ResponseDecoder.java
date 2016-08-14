/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.ProtocolViolationException;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.internal.http.ArmeriaHttpUtil;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
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
        NEED_DATA_OR_TRAILING_HEADERS,
        DISCARD
    }

    /** The request being decoded currently. */
    private HttpResponseWrapper res;
    private int resId = 1;
    private State state = State.NEED_HEADERS;

    Http1ResponseDecoder(Channel channel) {
        super(channel);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {}

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {}

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelRegistered();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelUnregistered();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpObject)) {
            ctx.fireChannelRead(msg);
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

                        final HttpResponseWrapper res = getResponse(resId);
                        assert res != null;
                        this.res = res;

                        if (nettyRes.status().codeClass() == HttpStatusClass.INFORMATIONAL) {
                            state = State.NEED_INFORMATIONAL_DATA;
                        } else {
                            state = State.NEED_DATA_OR_TRAILING_HEADERS;
                        }

                        res.scheduleTimeout(ctx);
                        res.write(ArmeriaHttpUtil.toArmeria(nettyRes));
                    } else {
                        failWithUnexpectedMessageType(ctx, msg);
                    }
                    break;
                case NEED_INFORMATIONAL_DATA:
                    if (msg instanceof LastHttpContent) {
                        state = State.NEED_HEADERS;
                    } else {
                        failWithUnexpectedMessageType(ctx, msg);
                    }
                    break;
                case NEED_DATA_OR_TRAILING_HEADERS:
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
                            final long maxContentLength = res.maxContentLength();
                            if (maxContentLength > 0 && res.writtenBytes() > maxContentLength - dataLength) {
                                fail(ctx, ContentTooLargeException.get());
                                return;
                            } else {
                                res.write(HttpData.of(data));
                            }
                        }

                        if (msg instanceof LastHttpContent) {
                            final HttpResponseWriter res = removeResponse(resId++);
                            assert this.res == res;
                            this.res = null;

                            state = State.NEED_HEADERS;

                            final HttpHeaders trailingHeaders = ((LastHttpContent) msg).trailingHeaders();
                            if (!trailingHeaders.isEmpty()) {
                                res.write(ArmeriaHttpUtil.toArmeria(trailingHeaders));
                            }

                            res.close();

                            if (needsToDisconnect()) {
                                ctx.close();
                            }
                        }
                    } else {
                        failWithUnexpectedMessageType(ctx, msg);
                    }
                    break;
                case DISCARD:
                    break;
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void failWithUnexpectedMessageType(ChannelHandlerContext ctx, Object msg) {
        fail(ctx, new ProtocolViolationException(
                "unexpected message type: " + msg.getClass().getName()));
    }

    private void fail(ChannelHandlerContext ctx, Throwable cause) {
        state = State.DISCARD;

        final HttpResponseWriter res = this.res;
        this.res = null;

        if (res != null) {
            res.close(cause);
        } else {
            logger.warn("Unexpected exception:", cause);
        }

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
}
