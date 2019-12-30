/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import io.netty.channel.EventLoop;

/**
 * A controller that is set to a deadline or resets to a timeout when the timeout setting is changed.
 *
 * <p>Note: This class is meant for internal use to schedule an initial timeout task or
 * reschedule a timeout task when a user updates the timeout configuration.
 */
public final class DefaultTimeoutController implements TimeoutController {

    private final TimeoutTask timeoutTask;
    private final Supplier<? extends EventLoop> eventLoopSupplier;

    private long timeoutMillis;
    private long firstStartTimeNanos;
    private long lastStartTimeNanos;

    @Nullable
    private ScheduledFuture<?> timeoutFuture;

    /**
     * Creates a new instance.
     */
    public DefaultTimeoutController(TimeoutTask timeoutTask,
                             Supplier<? extends EventLoop> eventLoopSupplier) {
        this(timeoutTask, eventLoopSupplier, 0);
    }

    /**
     * Creates a new instance.
     */
    public DefaultTimeoutController(TimeoutTask timeoutTask,
                             Supplier<? extends EventLoop> eventLoopSupplier,
                             long timeoutMillis) {
        requireNonNull(timeoutTask, "timeoutTask");
        requireNonNull(eventLoopSupplier, "eventLoopSupplier");
        this.timeoutTask = timeoutTask;
        this.eventLoopSupplier = eventLoopSupplier;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void initTimeout() {
        initTimeout(timeoutMillis);
    }

    @Override
    public void initTimeout(long timeoutMillis) {
        if (timeoutFuture != null || timeoutMillis <= 0 || !timeoutTask.isReady()) {
            // No need to schedule a response timeout if:
            // - the timeout has been scheduled already,
            // - the timeout has been disabled or
            // - the status is not ready yet.
            return;
        }
        this.timeoutMillis = timeoutMillis;
        firstStartTimeNanos = lastStartTimeNanos = System.nanoTime();
        timeoutFuture = eventLoopSupplier.get().schedule(timeoutTask, timeoutMillis,
                                                         TimeUnit.MILLISECONDS);
    }

    @Override
    public void adjustTimeout(long adjustmentMillis) {
        ensureInitialized();

        if (adjustmentMillis == 0) {
            return;
        }

        // Cancel the previously scheduled timeout, if exists.
        cancelTimeout();

        if (!timeoutTask.canReschedule()) {
            // Calculate the amount of time passed since the creation of this subscriber.
            final long currentNanoTime = System.nanoTime();
            final long passedTimeMillis = TimeUnit.NANOSECONDS.toMillis(currentNanoTime - lastStartTimeNanos);
            final long newTimeoutMillis = timeoutMillis + adjustmentMillis - passedTimeMillis;
            timeoutMillis = newTimeoutMillis;
            lastStartTimeNanos = currentNanoTime;
            if (newTimeoutMillis > 0) {
                timeoutFuture = eventLoopSupplier.get().schedule(
                        timeoutTask, newTimeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                // We went past the dead line set by the new timeout already.
                timeoutTask.onTimeout();
            }
        }
    }

    @Override
    public void resetTimeout(long newTimeoutMillis) {
        ensureInitialized();
        if (newTimeoutMillis <= 0) {
            timeoutMillis = newTimeoutMillis;
            cancelTimeout();
            return;
        }

        if (timeoutTask.canReschedule()) {
            final long currentNanoTime = System.nanoTime();
            final long passedTimeMillis = TimeUnit.NANOSECONDS.toMillis(currentNanoTime - lastStartTimeNanos);
            final long remainingTimeoutMillis = timeoutMillis - passedTimeMillis;
            lastStartTimeNanos = currentNanoTime;
            if (remainingTimeoutMillis == newTimeoutMillis) {
                return;
            }

            // Cancel the previously scheduled timeout, if exists.
            cancelTimeout();
            timeoutMillis = newTimeoutMillis;
            timeoutFuture = eventLoopSupplier.get().schedule(timeoutTask, newTimeoutMillis,
                                                             TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public boolean cancelTimeout() {
        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        if (timeoutFuture == null) {
            return true;
        }

        this.timeoutFuture = null;
        return timeoutFuture.cancel(false);
    }

    private void ensureInitialized() {
        checkState(firstStartTimeNanos > 0,
                   "initTimeout(timeoutMillis) is not called yet.");
    }

    /**
     * Returns the start time of the initial timeout in nanoseconds.
     */
    @Override
    public long startTimeNanos() {
        return firstStartTimeNanos;
    }

    @VisibleForTesting
    long timeoutMillis() {
        return timeoutMillis;
    }

    @Nullable
    @VisibleForTesting
    ScheduledFuture<?> timeoutFuture() {
        return timeoutFuture;
    }

    /**
     * A timeout task that is invoked when the deadline exceeded.
     */
    public interface TimeoutTask extends Runnable {
        /**
         * Returns {@code true} the timeout task is ready to start.
         */
        boolean isReady();

        /**
         * Returns {@code true} if the timeout task can be rescheduled.
         */
        boolean canReschedule();

        /**
         * Invoked when the deadline exceeded.
         */
        void onTimeout();

        @Override
        default void run() {
            onTimeout();
        }
    }
}
