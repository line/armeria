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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;

/**
 * Limit the number of open connections to the configured value.
 * {@link ConnectionLimitingHandler} instance would be set to {@link ServerBootstrap#handler(ChannelHandler)}.
 */
@Sharable
public final class ConnectionLimitingHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionLimitingHandler.class);

    private final int maxNumConnections;
    private final AtomicInteger numConnections = new AtomicInteger();

    private final AtomicBoolean droppedLogLock = new AtomicBoolean();
    private final LongAdder numDroppedConnections = new LongAdder();

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

            // Set linger option to 0 so that the server doesn't get too many TIME_WAIT states.
            child.config().setOption(ChannelOption.SO_LINGER, 0);
            child.unsafe().closeForcibly();

            numDroppedConnections.increment();

            if (droppedLogLock.compareAndSet(false, true)) {
                ctx.executor().schedule(this::writeNumDroppedConnectionsLog, 1, TimeUnit.SECONDS);
            }
        }
    }

    private void writeNumDroppedConnectionsLog() {
        droppedLogLock.set(false);

        long dropped = numDroppedConnections.sumThenReset();
        if (dropped > 0) {
            logger.warn("Dropped {} connection(s) to limit the number of open connections to {}",
                        dropped, maxNumConnections);
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
