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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.concurrent.EventExecutor;

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
    private final EventExecutor executor;

    private long timeoutNanos;
    private long firstExecutionTimeNanos;
    private long lastExecutionTimeNanos;

    private State state = State.INIT;

    @Nullable
    private ScheduledFuture<?> timeoutFuture;
    @Nullable
    private CompletableFuture<Void> whenTimingOut;
    @Nullable
    private CompletableFuture<Void> whenTimedOut;

    /**
     * Creates a new instance with the specified {@link TimeoutTask} and {@link EventExecutor}.
     */
    public DefaultTimeoutController(TimeoutTask timeoutTask, EventExecutor executor) {
        requireNonNull(timeoutTask, "timeoutTask");
        requireNonNull(executor, "executor");
        this.timeoutTask = timeoutTask;
        this.executor = executor;
    }

    /**
     * Creates a new instance with the specified {@link EventExecutor}.
     */
    public DefaultTimeoutController(EventExecutor executor) {
        requireNonNull(executor, "executor");
        this.executor = executor;
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
     * the {@link #DefaultTimeoutController(TimeoutTask, EventExecutor)} before calling this method.
     *
     * @return {@code true} if the timeout is scheduled.
     *         {@code false} if the timeout has been scheduled, the timeout has been triggered already
     *         or the {@link TimeoutTask#canSchedule()} returned {@code false}.
     */
    @Override
    public boolean scheduleTimeoutNanos(long timeoutNanos) {
        checkArgument(timeoutNanos > 0,
                      "timeoutNanos: %s (expected: > 0)", timeoutNanos);
        ensureInitialized();
        if (state != State.INACTIVE || !timeoutTask.canSchedule()) {
            return false;
        }

        cancelTimeout();
        this.timeoutNanos = timeoutNanos;
        state = State.SCHEDULED;
        timeoutFuture = executor.schedule(this::invokeTimeoutTask, timeoutNanos, TimeUnit.NANOSECONDS);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that the {@link TimeoutTask} should be set via the {@link #setTimeoutTask(TimeoutTask)} or
     * the {@link #DefaultTimeoutController(TimeoutTask, EventExecutor)} before calling this method.
     *
     * @return {@code true} if the current timeout is extended by the specified {@code adjustmentNanos}.
     *         {@code false} if no timeout was scheduled previously, the timeout has been triggered already
     *         or the {@link TimeoutTask#canSchedule()} returned {@code false}.
     */
    @Override
    public boolean extendTimeoutNanos(long adjustmentNanos) {
        ensureInitialized();
        if (state != State.SCHEDULED || !timeoutTask.canSchedule()) {
            return false;
        }

        if (adjustmentNanos == 0) {
            return true;
        }

        // Cancel the previously scheduled timeout, if exists.
        cancelTimeout();

        // Calculate the amount of time passed since lastStart
        final long currentTimeNanos = System.nanoTime();
        final long passedTimeNanos = currentTimeNanos - lastExecutionTimeNanos;
        final long newTimeoutNanos = LongMath.saturatedAdd(
                LongMath.saturatedSubtract(timeoutNanos, passedTimeNanos), adjustmentNanos);
        timeoutNanos = newTimeoutNanos;
        lastExecutionTimeNanos = currentTimeNanos;

        if (newTimeoutNanos <= 0) {
            invokeTimeoutTask();
            return true;
        }

        state = State.SCHEDULED;
        timeoutFuture = executor.schedule(this::invokeTimeoutTask, newTimeoutNanos, TimeUnit.NANOSECONDS);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Note that the {@link TimeoutTask} should be set via the {@link #setTimeoutTask(TimeoutTask)} or
     * the {@link #DefaultTimeoutController(TimeoutTask, EventExecutor)} before calling this method.
     *
     * @return {@code true} if the current timeout is reset by the specified {@code newTimeoutNanos}.
     *         {@code false} if the timeout has been triggered already
     *         or the {@link TimeoutTask#canSchedule()} returned {@code false}.
     */
    @Override
    public boolean resetTimeoutNanos(long newTimeoutNanos) {
        ensureInitialized();
        if (state == State.TIMED_OUT || !timeoutTask.canSchedule()) {
            return false;
        }
        if (newTimeoutNanos <= 0) {
            timeoutNanos = newTimeoutNanos;
            cancelTimeout();
            return true;
        }

        // Cancel the previously scheduled timeout, if exists.
        cancelTimeout();
        timeoutNanos = newTimeoutNanos;
        state = State.SCHEDULED;
        timeoutFuture = executor.schedule(this::invokeTimeoutTask, newTimeoutNanos, TimeUnit.NANOSECONDS);
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

    @Override
    public CompletableFuture<Void> whenTimingOut() {
        if (whenTimingOut == null) {
            whenTimingOut = new CompletableFuture<>();
        }
        return whenTimingOut;
    }

    @Override
    public CompletableFuture<Void> whenTimedOut() {
        if (whenTimedOut == null) {
            whenTimedOut = new CompletableFuture<>();
        }
        return whenTimedOut;
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
            if (whenTimingOut != null) {
                if (timeoutTask.canSchedule()) {
                    whenTimingOut.complete(null);
                }
            } else {
                whenTimingOut = UnmodifiableFuture.completedFuture(null);
            }

            // Set TIMED_OUT flag first to prevent duplicate execution
            state = State.TIMED_OUT;

            // The returned value of `canSchedule()` could be changed by the callbacks of `whenTimedOut`
            if (timeoutTask.canSchedule()) {
                timeoutTask.run();
            }

            if (whenTimedOut != null) {
                whenTimedOut.complete(null);
            } else {
                whenTimedOut = UnmodifiableFuture.completedFuture(null);
            }
        }
    }

    @Override
    public Long startTimeNanos() {
        return state != State.INIT ? firstExecutionTimeNanos : null;
    }

    @VisibleForTesting
    long timeoutNanos() {
        return timeoutNanos;
    }

    @Nullable
    @VisibleForTesting
    ScheduledFuture<?> timeoutFuture() {
        return timeoutFuture;
    }

    @Nullable
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
