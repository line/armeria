/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;

/**
 * Abstract class that's used to implement protocol-specific graceful connection shutdown logic.
 *
 * <p>This class is <b>not</b> thread-safe and all methods should be called from a single thread such
 * as {@link EventLoop}.
 */
public abstract class GracefulConnectionShutdownHandler {
    private static final Logger logger = LoggerFactory.getLogger(GracefulConnectionShutdownHandler.class);

    private boolean started;
    @Nullable
    private ScheduledFuture<?> drainFuture;

    // Drain duration in microseconds used during the graceful connection shutdown start.
    private long drainDurationMicros;
    private boolean canCallOnDrainStart = true;

    protected GracefulConnectionShutdownHandler(long drainDurationMicros) {
        this.drainDurationMicros = drainDurationMicros;
    }

    /**
     * Code executed on the connection drain start. Executed at most once.
     * Not executed if the drain duration is {@code 0}.
     */
    protected abstract void onDrainStart(ChannelHandlerContext ctx);

    /**
     * Code executed on the connection drain end. Executed at most once.
     */
    protected abstract void onDrainEnd(ChannelHandlerContext ctx) throws Exception;

    public void start(ChannelHandlerContext ctx, ChannelPromise promise) {
        final Channel ch = ctx.channel();
        if (!ch.isActive()) {
            promise.trySuccess();
            return;
        }

        started = true;

        // Ensure the given promise is fulfilled when the channel is closed.
        ch.closeFuture().addListener((ChannelFutureListener) future -> {
            if (future.cause() != null) {
                promise.tryFailure(future.cause());
            } else {
                promise.trySuccess();
            }
        });

        run(ctx);
    }

    private void run(ChannelHandlerContext ctx) {
        if (drainFuture != null) {
            final boolean cancelled;
            if (drainFuture.getDelay(TimeUnit.MICROSECONDS) > drainDurationMicros) {
                // Maybe reschedule below.
                cancelled = drainFuture.cancel(false);
            } else {
                cancelled = false;
            }

            if (cancelled) {
                drainFuture = null;
            } else {
                // Drain is already scheduled to finish earlier.
                return;
            }
        }
        if (drainDurationMicros > 0) {
            if (canCallOnDrainStart) {
                onDrainStart(ctx);
            }
            drainFuture = ctx.executor().schedule(() -> finish(ctx),
                                                  drainDurationMicros, TimeUnit.MICROSECONDS);
        } else {
            finish(ctx);
        }
        canCallOnDrainStart = false;
    }

    private void finish(ChannelHandlerContext ctx) {
        try {
            onDrainEnd(ctx);
        } catch (Exception e) {
            logger.warn("{} Unexpected exception:", ctx.channel(), e);
        }
    }

    public void cancel() {
        if (drainFuture != null && drainFuture.cancel(false)) {
            drainFuture = null;
        }
    }

    public void handleInitiateConnectionShutdown(ChannelHandlerContext ctx, InitiateConnectionShutdown event) {
        // If the given duration is negative - fallback to the default duration set in the constructor.
        if (event.hasCustomDrainDuration()) {
            // This value will be used to schedule or update the graceful connection shutdown drain duration.
            drainDurationMicros = event.drainDurationMicros();
        }

        if (started) {
            // Maybe reschedule drain end.
            run(ctx);
        } else {
            // Shutdown not started yet, close the channel to trigger start().
            final Channel ch = ctx.channel();
            if (ch.isActive()) {
                ch.close();
            }
        }
    }
}
