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
package com.linecorp.armeria.internal;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.util.TimeoutController;

import io.netty.channel.EventLoop;

/**
 * Default {@link TimeoutController} implementation.
 */
public class DefaultTimeoutController implements TimeoutController {

    @Nullable
    private TimeoutTask timeoutTask;
    private final EventLoop eventLoop;

    private long timeoutMillis;
    private long firstStartTimeNanos;
    private long lastStartTimeNanos;

    @Nullable
    private ScheduledFuture<?> timeoutFuture;

    /**
     * Creates a new instance with the specified {@link TimeoutTask} and {@link EventLoop}.
     */
    public DefaultTimeoutController(TimeoutTask timeoutTask, EventLoop eventLoop) {
        requireNonNull(timeoutTask, "timeoutTask");
        requireNonNull(eventLoop, "eventLoop");
        this.timeoutTask = timeoutTask;
        this.eventLoop = eventLoop;
    }

    /**
     * Creates a new instance with the specified {@link EventLoop}.
     */
    public DefaultTimeoutController(EventLoop eventLoop) {
        requireNonNull(eventLoop, "eventLoop");
        this.eventLoop = eventLoop;
    }

    /**
     * Sets the {@link TimeoutTask} that is invoked when the deadline exceeded.
     */
    public void setTimeoutTask(TimeoutTask timeoutTask) {
        this.timeoutTask = requireNonNull(timeoutTask, "timeoutTask");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that the {@link TimeoutTask} should be set via the {@link #setTimeoutTask(TimeoutTask)} or
     * the {@link #DefaultTimeoutController(TimeoutTask, EventLoop)} before calling this method.
     */
    @Override
    public void initTimeout(long timeoutMillis) {
        if (timeoutFuture != null || timeoutMillis <= 0 || timeoutTask == null || !timeoutTask.isReady()) {
            // No need to schedule a response timeout if:
            // - the timeout has been scheduled already,
            // - the timeout has been disabled or
            // - the status is not ready yet.
            return;
        }
        this.timeoutMillis = timeoutMillis;
        firstStartTimeNanos = lastStartTimeNanos = System.nanoTime();
        timeoutFuture = eventLoop.schedule(timeoutTask, timeoutMillis,
                                           TimeUnit.MILLISECONDS);
    }

    @Override
    public void extendTimeout(long adjustmentMillis) {
        ensureInitialized();
        if (adjustmentMillis == 0) {
            return;
        }

        // Cancel the previously scheduled timeout, if exists.
        cancelTimeout();

        if (timeoutTask.canReschedule()) {
            // Calculate the amount of time passed since the creation of this subscriber.
            final long currentNanoTime = System.nanoTime();
            final long passedTimeMillis = TimeUnit.NANOSECONDS.toMillis(currentNanoTime - lastStartTimeNanos);
            // newTimeoutMillis = timeoutMillis - passedTimeMillis + adjustmentMillis
            final long newTimeoutMillis = LongMath.saturatedAdd(
                    LongMath.saturatedSubtract(timeoutMillis, passedTimeMillis), adjustmentMillis);
            timeoutMillis = newTimeoutMillis;
            lastStartTimeNanos = currentNanoTime;
            if (newTimeoutMillis > 0) {
                timeoutFuture = eventLoop.schedule(
                        timeoutTask, newTimeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                // We went past the dead line set by the new timeout already.
                timeoutTask.run();
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
            final long remainingTimeoutMillis = LongMath.saturatedSubtract(timeoutMillis, passedTimeMillis);
            lastStartTimeNanos = currentNanoTime;
            if (remainingTimeoutMillis == newTimeoutMillis) {
                return;
            }

            // Cancel the previously scheduled timeout, if exists.
            cancelTimeout();
            timeoutMillis = newTimeoutMillis;
            timeoutFuture = eventLoop.schedule(timeoutTask, newTimeoutMillis,
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
        checkState(timeoutTask != null,
                   "setTimeoutTask(timeoutTask) is not called yet.");
        checkState(firstStartTimeNanos > 0,
                   "initTimeout(timeoutMillis) is not called yet.");
    }

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
         * Returns {@code true} if the timeout task is ready to start.
         */
        boolean isReady();

        /**
         * Returns {@code true} if the timeout task can be rescheduled.
         */
        boolean canReschedule();

        /**
         * Invoked when the deadline exceeded.
         */
        @Override
        void run();
    }
}
