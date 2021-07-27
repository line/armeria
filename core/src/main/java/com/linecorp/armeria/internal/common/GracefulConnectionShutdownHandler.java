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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Nullable
    private ChannelPromise promise;
    // Drain duration in microseconds used during the graceful connection shutdown start.
    private long drainDurationMicros;
    private boolean canCallOnDrainStart = true;
    @Nullable
    private ScheduledFuture<?> drainFuture;

    protected GracefulConnectionShutdownHandler(long drainDurationMicros) {
        setDrainDurationMicros(drainDurationMicros);
    }

    private void setDrainDurationMicros(long drainDurationMicros) {
        checkArgument(drainDurationMicros >= 0, "count: %s (expected: >= 0", drainDurationMicros);
        this.drainDurationMicros = drainDurationMicros;
    }

    /**
     * Code executed on the connection drain start. Executed at most once.
     * Not executed if the drain duration is {@code 0}.
     */
    public abstract void onDrainStart(ChannelHandlerContext ctx);

    /**
     * Code executed on the connection drain end. Executed at most once.
     */
    public abstract void onDrainEnd(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception;

    public void start(ChannelHandlerContext ctx, ChannelPromise promise) {
        if (this.promise == null) {
            this.promise = promise;
        } else {
            // Chain promises in case start is called multiple times.
            this.promise.addListener((ChannelFutureListener) future -> {
                if (future.cause() != null) {
                    promise.setFailure(future.cause());
                } else {
                    promise.setSuccess();
                }
            });
        }
        run(ctx, this.promise);
    }

    private void run(ChannelHandlerContext ctx, ChannelPromise promise) {
        if (drainFuture != null) {
            if (drainFuture.getDelay(TimeUnit.MICROSECONDS) > drainDurationMicros) {
                // Maybe reschedule below.
                drainFuture.cancel(false);
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
            drainFuture = ctx.executor().schedule(() -> finish(ctx, promise),
                                                  drainDurationMicros, TimeUnit.MICROSECONDS);
        } else {
            finish(ctx, promise);
        }
        canCallOnDrainStart = false;
    }

    private void finish(ChannelHandlerContext ctx, ChannelPromise promise) {
        try {
            onDrainEnd(ctx, promise);
        } catch (Exception e) {
            logger.warn("Unexpected exception:", e);
        }
    }

    public void cancel() {
        if (drainFuture != null) {
            drainFuture.cancel(false);
            drainFuture = null;
        }
    }

    public void handleInitiateConnectionShutdown(ChannelHandlerContext ctx, InitiateConnectionShutdown event) {
        // If the given duration is negative - fallback to the default duration set in the constructor.
        if (event.hasCustomDrainDuration()) {
            // This value will be used during the graceful connection shutdown start.
            setDrainDurationMicros(event.drainDurationMicros());
        }
        if (promise == null) {
            // Shutdown not started yet, close the channel to start.
            ctx.channel().close();
            return;
        }
        // Maybe reschedule drain end.
        run(ctx, promise);
    }
}
