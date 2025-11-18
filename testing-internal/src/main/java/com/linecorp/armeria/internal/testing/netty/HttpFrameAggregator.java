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

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.util.ReferenceCountUtil;

final class HttpFrameAggregator extends SimpleChannelInboundHandler<Http2Frame> implements SafeCloseable {

    HttpFrameAggregator() {
        super(false);
    }

    private final BlockingDeque<Http2Frame> frames = new LinkedBlockingDeque<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Http2Frame msg) throws Exception {
        frames.offer(msg);
    }

    BlockingDeque<Http2Frame> frames() {
        return frames;
    }

    @Override
    public void close() {
        while (!frames.isEmpty()) {
            final Http2Frame frame = frames.poll();
            if (frame != null) {
                ReferenceCountUtil.release(frame);
            }
        }
    }
}
