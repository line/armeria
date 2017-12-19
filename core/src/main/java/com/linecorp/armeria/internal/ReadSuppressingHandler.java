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

package com.linecorp.armeria.internal;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;

/**
 * A {@link ChannelOutboundHandler} that suppresses unnecessary {@link ChannelHandlerContext#read()} calls
 * when auto-read is disabled.
 */
@Sharable
public final class ReadSuppressingHandler extends ChannelOutboundHandlerAdapter {

    /**
     * The singleton {@link ReadSuppressingHandler} instance.
     */
    public static final ReadSuppressingHandler INSTANCE = new ReadSuppressingHandler();

    private ReadSuppressingHandler() {}

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().config().isAutoRead()) {
            super.read(ctx);
        }
    }
}
