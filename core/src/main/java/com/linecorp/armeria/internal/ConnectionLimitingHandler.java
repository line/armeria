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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Limit the number of open connections to the configured value.
 * {@link ConnectionLimitingHandler} instance would be set to {@link ServerBootstrap#handler(ChannelHandler)}.
 */
@Sharable
public final class ConnectionLimitingHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionLimitingHandler.class);

    private final int maxNumConnections;
    private final AtomicInteger numConnections = new AtomicInteger();

    public ConnectionLimitingHandler(int maxNumConnections) {
        this.maxNumConnections = validateMaxNumConnections(maxNumConnections);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final Channel child = (Channel) msg;

        int conn = numConnections.incrementAndGet();
        if (conn > 0 && conn <= maxNumConnections) {
            child.closeFuture().addListener(future -> numConnections.decrementAndGet());
            super.channelRead(ctx, msg);
        } else {
            numConnections.decrementAndGet();
            child.unsafe().closeForcibly();
            logger.warn("Exceeds the maximum number of open connections: {}", child);
        }
    }

    /**
     * Returns the maximum allowed number of open connections.
     */
    public int maxNumConnections() {
        return maxNumConnections;
    }

    /**
     * Returns the number of open connections.
     */
    public int numConnections() {
        return numConnections.get();
    }

    /**
     * Validates the maximum allowed number of open connections. It must be a positive number.
     */
    public static int validateMaxNumConnections(int maxNumConnections) {
        if (maxNumConnections <= 0) {
            throw new IllegalArgumentException("maxNumConnections: " + maxNumConnections + " (expected: > 0)");
        }
        return maxNumConnections;
    }
}
