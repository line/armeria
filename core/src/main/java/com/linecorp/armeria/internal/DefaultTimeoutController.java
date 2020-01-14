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

import static com.google.common.base.Preconditions.checkArgument;
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
    private long firstExecutionTimeNanos;
    private long lastExecutionTimeNanos;

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
    public boolean scheduleTimeout(long timeoutMillis) {
        checkArgument(timeoutMillis > 0,
                      "timeoutMillis: " + timeoutMillis + " (expected: > 0)");
        // Do nothing if the timeout was scheduled already
        if (timeoutFuture != null) {
            return false;
        }

        cancelTimeout();
        if (!timeoutTask.canSchedule()) {
            return false;
        }

        this.timeoutMillis = timeoutMillis;
        final long nanoTime = System.nanoTime();
        if (firstExecutionTimeNanos == 0) {
            firstExecutionTimeNanos = nanoTime;
        }
        lastExecutionTimeNanos = nanoTime;
        timeoutFuture = eventLoop.schedule(timeoutTask, timeoutMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that the {@link TimeoutTask} should be set via the {@link #setTimeoutTask(TimeoutTask)} or
     * the {@link #DefaultTimeoutController(TimeoutTask, EventLoop)} before calling this method.
     */
    @Override
    public boolean extendTimeout(long adjustmentMillis) {
        ensureInitialized();
        if (adjustmentMillis == 0 || timeoutFuture == null) {
            return true;
        }

        // Cancel the previously scheduled timeout, if exists.
        cancelTimeout();
        if (!timeoutTask.canSchedule()) {
            return false;
        }

        // Calculate the amount of time passed since lastStart
        final long currentNanoTime = System.nanoTime();
        final long passedTimeMillis = TimeUnit.NANOSECONDS.toMillis(currentNanoTime - lastExecutionTimeNanos);
        // newTimeoutMillis = timeoutMillis - passedTimeMillis + adjustmentMillis
        final long newTimeoutMillis = LongMath.saturatedAdd(
                LongMath.saturatedSubtract(timeoutMillis, passedTimeMillis), adjustmentMillis);
        timeoutMillis = newTimeoutMillis;
        lastExecutionTimeNanos = currentNanoTime;
        if (newTimeoutMillis > 0) {
            timeoutFuture = eventLoop.schedule(
                    timeoutTask, newTimeoutMillis, TimeUnit.MILLISECONDS);
        } else {
            // We went past the dead line set by the new timeout already.
            timeoutTask.run();
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that the {@link TimeoutTask} should be set via the {@link #setTimeoutTask(TimeoutTask)} or
     * the {@link #DefaultTimeoutController(TimeoutTask, EventLoop)} before calling this method.
     */
    @Override
    public boolean resetTimeout(long newTimeoutMillis) {
        ensureInitialized();
        if (newTimeoutMillis <= 0) {
            timeoutMillis = newTimeoutMillis;
            cancelTimeout();
            return true;
        }

        if (!timeoutTask.canSchedule()) {
            return false;
        }

        // Cancel the previously scheduled timeout, if exists.
        cancelTimeout();
        timeoutMillis = newTimeoutMillis;
        timeoutFuture = eventLoop.schedule(timeoutTask, newTimeoutMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void timeoutNow() {
        checkState(timeoutTask != null,
                   "setTimeoutTask(timeoutTask) is not called yet.");
        if (cancelTimeout()) {
            timeoutTask.run();
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
        if (firstExecutionTimeNanos == 0) {
            firstExecutionTimeNanos = lastExecutionTimeNanos = System.nanoTime();
        }
    }

    @Override
    public long startTimeNanos() {
        return firstExecutionTimeNanos;
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
         * Returns {@code true} if the timeout task can be scheduled.
         */
        boolean canSchedule();

        /**
         * Invoked when the deadline exceeded.
         */
        @Override
        void run();
    }
}
