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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.testing.SimpleChannelHandler.ThrowingBiConsumer;
import com.linecorp.armeria.internal.testing.SimpleChannelHandler.ThrowingTriConsumer;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

public final class SimpleChannelHandlerFactory {

    @Nullable
    private final ThrowingBiConsumer<ChannelHandlerContext, Object> onChannelRead;
    @Nullable
    private final ThrowingTriConsumer<ChannelHandlerContext, Object, ChannelPromise> onWrite;

    public static SimpleChannelHandlerFactory onChannelRead(
            ThrowingBiConsumer<ChannelHandlerContext, Object> onChannelRead) {
        requireNonNull(onChannelRead, "onChannelRead");
        return new SimpleChannelHandlerFactory(onChannelRead, null);
    }

    public static SimpleChannelHandlerFactory onWrite(
            ThrowingTriConsumer<ChannelHandlerContext, Object, ChannelPromise> onWrite) {
        requireNonNull(onWrite, "onWrite");
        return new SimpleChannelHandlerFactory(null, onWrite);
    }

    public SimpleChannelHandlerFactory(
            @Nullable ThrowingBiConsumer<ChannelHandlerContext, Object> onChannelRead,
            @Nullable ThrowingTriConsumer<ChannelHandlerContext, Object, ChannelPromise> onWrite) {
        this.onChannelRead = onChannelRead;
        this.onWrite = onWrite;
    }

    public ChannelHandler newHandler() {
        return new SimpleChannelHandler(onChannelRead, onWrite);
    }
}
