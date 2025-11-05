/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.testing.netty;

import org.jspecify.annotations.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.SslContext;

final class Http2ClientFrameInitializer extends ChannelInitializer<Channel> {

    @Nullable
    private final SslContext sslCtx;
    @Nullable
    private final Http2FrameLogger frameLogger;

    Http2ClientFrameInitializer(@Nullable SslContext sslCtx, @Nullable Http2FrameLogger frameLogger) {
        this.sslCtx = sslCtx;
        this.frameLogger = frameLogger;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        if (sslCtx != null) {
            ch.pipeline().addFirst(sslCtx.newHandler(ch.alloc()));
        }
        final Http2FrameCodecBuilder builder = Http2FrameCodecBuilder.forClient();
        if (frameLogger != null) {
            builder.frameLogger(frameLogger);
        }
        final Http2FrameCodec http2FrameCodec = builder.build();
        ch.pipeline().addLast(http2FrameCodec);
        ch.pipeline().addLast(new Http2MultiplexHandler(new SimpleChannelInboundHandler() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            }
        }));
    }
}
