/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal;

import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

@Sharable
public final class ConnectionLimitingHandler extends ChannelInboundHandlerAdapter {

    private final int maxNumConnections;
    private final AtomicInteger numConnections = new AtomicInteger();

    public ConnectionLimitingHandler(int maxNumConnections) {
        this.maxNumConnections = validateMaxConnections(maxNumConnections);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        int conn = numConnections.incrementAndGet();
        if (conn > 0 && conn <= maxNumConnections) {
            ctx.channel().closeFuture().addListener(future -> numConnections.decrementAndGet());

            // Fire channelActive to the next handlers.
            super.channelActive(ctx);
        } else {
            numConnections.decrementAndGet();
            ctx.close();
        }
    }

    /**
     * Returns the maximum allowed number of open numConnections.
     */
    public int maxNumConnections() {
        return maxNumConnections;
    }

    /**
     * Returns the number of open numConnections.
     */
    public int numConnections() {
        return numConnections.get();
    }

    /**
     * Validates the maximum allowed number of open numConnections. It must be a positive number.
     */
    public static int validateMaxConnections(int maxConnections) {
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxNumConnections: " + maxConnections + " (expected: > 0)");
        }
        return maxConnections;
    }
}
