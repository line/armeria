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

import static com.linecorp.armeria.client.HttpResponseDecoder.contentTooLargeException;
import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;

import java.util.concurrent.TimeUnit;

import com.google.common.math.LongMath;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.internal.common.KeepAliveHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.util.ReferenceCountUtil;

final class WebSocketClientChannelHandler extends ChannelDuplexHandler {

    private final HttpResponseWrapper res;
    private final KeepAliveHandler keepAliveHandler;

    WebSocketClientChannelHandler(HttpResponseWrapper res, KeepAliveHandler keepAliveHandler) {
        this.res = res;
        this.keepAliveHandler = keepAliveHandler;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        keepAliveHandler.destroy();
        // Close the response after 3 seconds with the exception so that we can give a chance to the
        // WebSocketClient to finish the response normally if it receives the close frame from the server.
        res.eventLoop().schedule(() -> {
            res.close(ClosedSessionException.get());
            ctx.fireChannelInactive();
        }, 3, TimeUnit.SECONDS);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof ByteBuf) {
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
                return;
            }
            if (msg == EMPTY_LAST_CONTENT) {
                // HttpClientCodec produces this after creating the headers. We can just ignore it.
                return;
            }
            ctx.fireChannelRead(msg);
        } finally {
            ReferenceCountUtil.release(msg);
        }
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
