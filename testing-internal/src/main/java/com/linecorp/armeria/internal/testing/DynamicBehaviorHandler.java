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

import javax.annotation.Nullable;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

@Sharable
public class DynamicBehaviorHandler extends ChannelDuplexHandler {

    @Nullable
    private volatile ThrowingBiConsumer<ChannelHandlerContext, Object> channelReadCustomizer;
    @Nullable
    private volatile ThrowingTriConsumer<ChannelHandlerContext, Object, ChannelPromise> writeCustomizer;

    public void reset() {
        channelReadCustomizer = null;
        writeCustomizer = null;
    }

    public void setChannelReadCustomizer(
            ThrowingBiConsumer<ChannelHandlerContext, Object> channelReadCustomizer) {
        this.channelReadCustomizer = channelReadCustomizer;
    }

    public void setWriteCustomizer(
            ThrowingTriConsumer<ChannelHandlerContext, Object, ChannelPromise> writeCustomizer) {
        this.writeCustomizer = writeCustomizer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final ThrowingBiConsumer<ChannelHandlerContext, Object> customizerRef = channelReadCustomizer;
        if (customizerRef != null) {
            customizerRef.accept(ctx, msg);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        final ThrowingTriConsumer<ChannelHandlerContext, Object, ChannelPromise>
                customizerRef = writeCustomizer;
        if (customizerRef != null) {
            customizerRef.accept(ctx, msg, promise);
        } else {
            super.write(ctx, msg, promise);
        }
    }

    @FunctionalInterface
    public interface ThrowingTriConsumer<A,B,C> {
        void accept(A a, B b, C c) throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingBiConsumer<A,B> {
        void accept(A a, B b) throws Exception;
    }
}
