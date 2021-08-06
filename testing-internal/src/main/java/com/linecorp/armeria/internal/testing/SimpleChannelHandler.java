/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.testing;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

public final class SimpleChannelHandler extends ChannelDuplexHandler {

    @Nullable
    private final ThrowingBiConsumer<ChannelHandlerContext, Object> onChannelRead;
    @Nullable
    private final ThrowingTriConsumer<ChannelHandlerContext, Object, ChannelPromise> onWrite;

    SimpleChannelHandler(
            @Nullable ThrowingBiConsumer<ChannelHandlerContext, Object> onChannelRead,
            @Nullable ThrowingTriConsumer<ChannelHandlerContext, Object, ChannelPromise> onWrite) {
        this.onChannelRead = onChannelRead;
        this.onWrite = onWrite;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (onChannelRead != null) {
            onChannelRead.accept(ctx, msg);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (onWrite != null) {
            onWrite.accept(ctx, msg, promise);
        } else {
            super.write(ctx, msg, promise);
        }
    }

    @FunctionalInterface
    public interface ThrowingTriConsumer<A, B, C> {
        void accept(A a, B b, C c) throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingBiConsumer<A, B> {
        void accept(A a, B b) throws Exception;
    }
}
