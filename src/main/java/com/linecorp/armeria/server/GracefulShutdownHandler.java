/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import com.google.common.base.Ticker;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * A handler that keeps track of pending requests to allow shutdown to happen
 * after a fixed quiet period passes after the last one.
 */
@Sharable
final class GracefulShutdownHandler extends ChannelDuplexHandler {

    private final long quietPeriodNanos;
    private final Ticker ticker;
    private final Executor blockingTaskExecutor;

    /**
     * NOTE: {@link #updatedLastResTimeNanos} and {@link #lastResTimeNanos} are declared as non-volatile
     *       while using this field as a memory barrier.
     */
    private volatile int pendingResCount;
    private boolean updatedLastResTimeNanos;
    private long lastResTimeNanos;
    private Long shutdownStartTimeNanos;

    static GracefulShutdownHandler create(Duration quietPeriod, Executor blockingTaskExecutor) {
        return new GracefulShutdownHandler(quietPeriod, blockingTaskExecutor, Ticker.systemTicker());
    }

    GracefulShutdownHandler(Duration quietPeriod, Executor blockingTaskExecutor, Ticker ticker) {
        quietPeriodNanos = quietPeriod.toNanos();
        this.blockingTaskExecutor = blockingTaskExecutor;
        this.ticker = ticker;
    }

    boolean isRequestStart(Object msg) {
        return msg instanceof HttpRequest;
    }

    boolean isResponseEnd(Object msg) {
        return msg instanceof LastHttpContent;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        // Reset the shutdown start time, because this handler will be added to a pipeline
        // when a new connection is accepted.
        shutdownStartTimeNanos = null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (isRequestStart(msg)) {
            pendingResCount++;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (isResponseEnd(msg)) {
            lastResTimeNanos = ticker.read();
            updatedLastResTimeNanos = true;
            pendingResCount--;
        }
        ctx.write(msg, promise);
    }

    /**
     * Indicates the quiet period duration has passed since the last request.
     */
    boolean completedQuietPeriod() {
        if (shutdownStartTimeNanos == null) {
            shutdownStartTimeNanos = ticker.read();
        }

        if (pendingResCount != 0 || !completedBlockingTasks()) {
            return false;
        }

        final long shutdownStartTimeNanos = this.shutdownStartTimeNanos;
        final long currentTimeNanos = ticker.read();
        final long duration;
        if (updatedLastResTimeNanos) {
            duration = Math.min(currentTimeNanos - shutdownStartTimeNanos,
                                currentTimeNanos - lastResTimeNanos);
        } else {
            duration = currentTimeNanos - shutdownStartTimeNanos;
        }

        return duration >= quietPeriodNanos;
    }

    private boolean completedBlockingTasks() {
        if (!(blockingTaskExecutor instanceof ThreadPoolExecutor)) {
            // Cannot determine if there's a blocking task.
            return true;
        }

        final ThreadPoolExecutor threadPool = (ThreadPoolExecutor) blockingTaskExecutor;
        return threadPool.getQueue().isEmpty() && threadPool.getActiveCount() == 0;
    }
}
