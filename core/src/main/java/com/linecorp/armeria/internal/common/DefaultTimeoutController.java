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
package com.linecorp.armeria.internal.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.math.LongMath;

import io.netty.channel.EventLoop;

/**
 * Default {@link TimeoutController} implementation.
 */
public class DefaultTimeoutController implements TimeoutController {

    enum State {
        INIT,
        INACTIVE,
        SCHEDULED,
        TIMED_OUT,
    }

    @Nullable
    private TimeoutTask timeoutTask;
    private final EventLoop eventLoop;

    private long timeoutMillis;
    private long firstExecutionTimeNanos;
    private long lastExecutionTimeNanos;

    @Nullable
    private ScheduledFuture<?> timeoutFuture;
    private State state = State.INIT;

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
     *
     * @return {@code true} if the timeout is scheduled.
     *         {@code false} if the timeout has been scheduled, the timeout has been triggered already
     *         or the {@link TimeoutTask#canSchedule()} returned {@code false}.
     */
    @Override
    public boolean scheduleTimeout(long timeoutMillis) {
        checkArgument(timeoutMillis > 0,
                      "timeoutMillis: %s (expected: > 0)", timeoutMillis);
        ensureInitialized();
        if (state != State.INACTIVE || !timeoutTask.canSchedule()) {
            return false;
        }

        cancelTimeout();
        this.timeoutMillis = timeoutMillis;
        state = State.SCHEDULED;
        timeoutFuture = eventLoop.schedule(this::invokeTimeoutTask, timeoutMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that the {@link TimeoutTask} should be set via the {@link #setTimeoutTask(TimeoutTask)} or
     * the {@link #DefaultTimeoutController(TimeoutTask, EventLoop)} before calling this method.
     *
     * @return {@code true} if the current timeout is extended by the specified {@code adjustmentMillis}.
     *         {@code false} if no timeout was scheduled previously, the timeout has been triggered already
     *         or the {@link TimeoutTask#canSchedule()} returned {@code false}.
     */
    @Override
    public boolean extendTimeout(long adjustmentMillis) {
        ensureInitialized();
        if (state != State.SCHEDULED || !timeoutTask.canSchedule()) {
            return false;
        }

        if (adjustmentMillis == 0) {
            return true;
        }

        // Cancel the previously scheduled timeout, if exists.
        cancelTimeout();

        // Calculate the amount of time passed since lastStart
        final long currentNanoTime = System.nanoTime();
        final long passedTimeMillis = TimeUnit.NANOSECONDS.toMillis(currentNanoTime - lastExecutionTimeNanos);
        final long newTimeoutMillis = LongMath.saturatedAdd(
                LongMath.saturatedSubtract(timeoutMillis, passedTimeMillis), adjustmentMillis);
        timeoutMillis = newTimeoutMillis;
        lastExecutionTimeNanos = currentNanoTime;

        if (newTimeoutMillis <= 0) {
            invokeTimeoutTask();
            return true;
        }

        state = State.SCHEDULED;
        timeoutFuture = eventLoop.schedule(this::invokeTimeoutTask, newTimeoutMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that the {@link TimeoutTask} should be set via the {@link #setTimeoutTask(TimeoutTask)} or
     * the {@link #DefaultTimeoutController(TimeoutTask, EventLoop)} before calling this method.
     *
     * @return {@code true} if the current timeout is reset by the specified {@code newTimeoutMillis}.
     *         {@code false} if the timeout has been triggered already
     *         or the {@link TimeoutTask#canSchedule()} returned {@code false}.
     */
    @Override
    public boolean resetTimeout(long newTimeoutMillis) {
        ensureInitialized();
        if (state == State.TIMED_OUT || !timeoutTask.canSchedule()) {
            return false;
        }
        if (newTimeoutMillis <= 0) {
            timeoutMillis = newTimeoutMillis;
            cancelTimeout();
            return true;
        }

        // Cancel the previously scheduled timeout, if exists.
        cancelTimeout();
        timeoutMillis = newTimeoutMillis;
        state = State.SCHEDULED;
        timeoutFuture = eventLoop.schedule(this::invokeTimeoutTask, newTimeoutMillis, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * Trigger the current timeout immediately.
     *
     * @return {@code true} if the current timeout is triggered successfully.
     *         {@code false} the timeout has been triggered already
     *         or the {@link TimeoutTask#canSchedule()} returned {@code false}.
     */
    @Override
    public boolean timeoutNow() {
        checkState(timeoutTask != null,
                   "setTimeoutTask(timeoutTask) is not called yet.");

        if (!timeoutTask.canSchedule()) {
            return false;
        }

        switch (state) {
            case TIMED_OUT:
                return false;
            case INIT:
            case INACTIVE:
                invokeTimeoutTask();
                return true;
            case SCHEDULED:
                if (cancelTimeout()) {
                    invokeTimeoutTask();
                    return true;
                } else {
                    return false;
                }
            default:
                throw new Error(); // Should not reach here.
        }
    }

    @Override
    public boolean cancelTimeout() {
        switch (state) {
            case INIT:
            case INACTIVE:
            case TIMED_OUT:
                return false;
        }

        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        assert timeoutFuture != null;

        final boolean canceled = timeoutFuture.cancel(false);
        this.timeoutFuture = null;
        if (canceled) {
            state = State.INACTIVE;
        }
        return canceled;
    }

    @Override
    public boolean isTimedOut() {
        return state == State.TIMED_OUT;
    }

    private void ensureInitialized() {
        checkState(timeoutTask != null,
                   "setTimeoutTask(timeoutTask) is not called yet.");
        if (state == State.INIT) {
            state = State.INACTIVE;
            firstExecutionTimeNanos = lastExecutionTimeNanos = System.nanoTime();
        }
    }

    private void invokeTimeoutTask() {
        if (timeoutTask != null) {
            // Set TIMED_OUT flag first to prevent duplicate execution
            state = State.TIMED_OUT;
            timeoutTask.run();
        }
    }

    @Override
    public Long startTimeNanos() {
        return state != State.INIT ? firstExecutionTimeNanos : null;
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

    @VisibleForTesting
    TimeoutTask timeoutTask() {
        return timeoutTask;
    }

    @VisibleForTesting
    State state() {
        return state;
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
