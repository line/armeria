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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.concurrent.EventExecutor;

public final class TimeoutScheduler {

    private static final AtomicReferenceFieldUpdater<TimeoutScheduler, TimeoutFuture>
            whenTimingOutUpdater = AtomicReferenceFieldUpdater.newUpdater(
            TimeoutScheduler.class, TimeoutFuture.class, "whenTimingOut");

    private static final AtomicReferenceFieldUpdater<TimeoutScheduler, TimeoutFuture>
            whenTimedOutUpdater = AtomicReferenceFieldUpdater.newUpdater(
            TimeoutScheduler.class, TimeoutFuture.class, "whenTimedOut");

    private static final AtomicReferenceFieldUpdater<TimeoutScheduler, Runnable>
            pendingTimeoutTaskUpdater = AtomicReferenceFieldUpdater.newUpdater(
            TimeoutScheduler.class, Runnable.class, "pendingTimeoutTask");

    private static final AtomicLongFieldUpdater<TimeoutScheduler> pendingTimeoutNanosUpdater =
            AtomicLongFieldUpdater.newUpdater(TimeoutScheduler.class, "pendingTimeoutNanos");

    private static final TimeoutFuture COMPLETED_FUTURE;

    static {
        COMPLETED_FUTURE = new TimeoutFuture();
        COMPLETED_FUTURE.doComplete();
    }

    enum State {
        INIT,
        INACTIVE,
        SCHEDULED,
        TIMED_OUT
    }

    private long timeoutNanos;
    private long firstExecutionTimeNanos;

    private State state = State.INIT;

    @Nullable
    private TimeoutTask timeoutTask;
    @Nullable
    private ScheduledFuture<?> timeoutFuture;
    @Nullable
    private EventExecutor eventLoop;

    // Updated via whenTimingOutUpdater
    @Nullable
    private volatile TimeoutFuture whenTimingOut;
    // Updated via whenTimedOutUpdater
    @Nullable
    private volatile TimeoutFuture whenTimedOut;
    // Updated via pendingTimeoutTaskUpdater
    @Nullable
    private volatile Runnable pendingTimeoutTask;
    // Updated via pendingTimeoutNanosUpdater
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long pendingTimeoutNanos;

    private volatile boolean initialized;

    public TimeoutScheduler(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
        pendingTimeoutNanos = timeoutNanos;
    }

    /**
     * Initializes this {@link TimeoutScheduler}.
     */
    public void init(EventExecutor eventLoop, TimeoutTask timeoutTask, long initialTimeoutNanos) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> init(eventLoop, timeoutTask, initialTimeoutNanos));
            return;
        }

        this.eventLoop = eventLoop;
        this.timeoutTask = timeoutTask;
        if (initialTimeoutNanos > 0) {
            timeoutNanos = initialTimeoutNanos;
        }

        ensureInitialized();
        if (timeoutNanos != 0) {
            state = State.SCHEDULED;
            timeoutFuture = eventLoop.schedule(this::invokeTimeoutTask, timeoutNanos, TimeUnit.NANOSECONDS);
        }

        Runnable pendingTimeoutTask;
        for (;;) {
            pendingTimeoutTask = this.pendingTimeoutTask;
            if (pendingTimeoutTaskUpdater.compareAndSet(this, pendingTimeoutTask, null)) {
                break;
            }
        }

        if (pendingTimeoutTask != null) {
            pendingTimeoutTask.run();
        }
        initialized = true;
        final Runnable newlyAdded = this.pendingTimeoutTask;
        if (newlyAdded != null) {
            newlyAdded.run();
        }
    }

    public void clearTimeout() {
       clearTimeout(true);
    }

    public void clearTimeout(boolean resetTimeout) {
        if (timeoutNanos == 0) {
            return;
        }

        if (initialized) {
            if (eventLoop.inEventLoop()) {
                unsafeClearTimeout(resetTimeout);
            } else {
                eventLoop.execute(() -> unsafeClearTimeout(resetTimeout));
            }
        } else {
            setPendingTimeoutNanos(0);
            addPendingTimeoutTask(() -> unsafeClearTimeout(resetTimeout));
        }
    }

    private boolean unsafeClearTimeout(boolean resetTimeout) {
        switch (state) {
            case INIT:
            case INACTIVE:
            case TIMED_OUT:
                return true;
        }

        if (resetTimeout) {
            timeoutNanos = 0;
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

    public void setTimeoutNanos(TimeoutMode mode, long timeoutNanos) {
        switch (mode) {
            case SET_FROM_NOW:
                setTimeoutNanosFromNow(timeoutNanos);
                break;
            case SET_FROM_START:
                setTimeoutNanosFromStart(timeoutNanos);
                break;
            case EXTEND:
                extendTimeoutNanos(timeoutNanos);
                break;
        }
    }

    private void setTimeoutNanosFromStart(long timeoutNanos) {
        checkArgument(timeoutNanos >= 0, "timeoutNanos: %s (expected: >= 0)", timeoutNanos);
        if (timeoutNanos == 0) {
            clearTimeout();
            return;
        }

        if (this.timeoutNanos == 0) {
            setTimeoutNanosFromNow(timeoutNanos);
            return;
        }

        final long adjustmentNanos = LongMath.saturatedSubtract(timeoutNanos, this.timeoutNanos);
        extendTimeoutNanos(adjustmentNanos);
    }

    private void extendTimeoutNanos(long adjustmentNanos) {
        if (adjustmentNanos == 0 || timeoutNanos == 0) {
            return;
        }

        if (initialized) {
            if (eventLoop.inEventLoop()) {
                unsafeExtendTimeoutNanos(adjustmentNanos);
            } else {
                eventLoop.execute(() -> unsafeExtendTimeoutNanos(adjustmentNanos));
            }
        } else {
            addPendingTimeoutNanos(adjustmentNanos);
            addPendingTimeoutTask(() -> unsafeExtendTimeoutNanos(adjustmentNanos));
        }
    }

    private boolean unsafeExtendTimeoutNanos(long adjustmentNanos) {
        ensureInitialized();
        if (state != State.SCHEDULED || !timeoutTask.canSchedule()) {
            return false;
        }

        if (adjustmentNanos == 0) {
            return true;
        }

        final long timeoutNanos = this.timeoutNanos;
        // Cancel the previously scheduled timeout, if exists.
        unsafeClearTimeout(true);

        this.timeoutNanos = LongMath.saturatedAdd(timeoutNanos, adjustmentNanos);

        if (timeoutNanos <= 0) {
            invokeTimeoutTask();
            return true;
        }

        state = State.SCHEDULED;
        timeoutFuture = eventLoop.schedule(this::invokeTimeoutTask,
                                           LongMath.saturatedAdd(timeoutNanos, adjustmentNanos),
                                           TimeUnit.NANOSECONDS);
        return true;
    }

    private void setTimeoutNanosFromNow(long timeoutNanos) {
        checkArgument(timeoutNanos > 0, "timeoutNanos: %s (expected: > 0)", timeoutNanos);

        if (initialized) {
            if (eventLoop.inEventLoop()) {
                unsafeSetTimeoutNanosFromNow(timeoutNanos);
            } else {
                eventLoop.execute(() -> unsafeSetTimeoutNanosFromNow(timeoutNanos));
            }
        } else {
            final long startTimeNanos = System.nanoTime();
            setPendingTimeoutNanos(timeoutNanos);
            addPendingTimeoutTask(() -> {
                final long passedTimeNanos0 = System.nanoTime() - startTimeNanos;
                final long timeoutNanos0 = Math.max(1, timeoutNanos - passedTimeNanos0);
                unsafeSetTimeoutNanosFromNow(timeoutNanos0);
            });
        }
    }

    private boolean unsafeSetTimeoutNanosFromNow(long newTimeoutNanos) {
        ensureInitialized();
        if (state == State.TIMED_OUT || !timeoutTask.canSchedule()) {
            return false;
        }

        // Cancel the previously scheduled timeout, if exists.
        unsafeClearTimeout(true);

        final long passedTimeNanos = System.nanoTime() - firstExecutionTimeNanos;
        timeoutNanos = LongMath.saturatedAdd(newTimeoutNanos, passedTimeNanos);

        if (newTimeoutNanos <= 0) {
            return true;
        }

        state = State.SCHEDULED;
        timeoutFuture = eventLoop.schedule(this::invokeTimeoutTask, newTimeoutNanos, TimeUnit.NANOSECONDS);
        return true;
    }

    public void timeoutNow() {
        if (initialized) {
            if (eventLoop.inEventLoop()) {
                unsafeTimeoutNow();
            } else {
                eventLoop.execute(this::unsafeTimeoutNow);
            }
        } else {
            addPendingTimeoutTask(this::unsafeTimeoutNow);
        }
    }

    private void unsafeTimeoutNow() {
        checkState(timeoutTask != null,
                   "init(eventLoop, timeoutTask) is not called yet.");

        if (!timeoutTask.canSchedule()) {
            return;
        }

        switch (state) {
            case TIMED_OUT:
                return;
            case INIT:
            case INACTIVE:
                invokeTimeoutTask();
                return;
            case SCHEDULED:
                if (unsafeClearTimeout(false)) {
                    invokeTimeoutTask();
                }
                return;
            default:
                throw new Error(); // Should not reach here.
        }
    }

    private void addPendingTimeoutTask(Runnable pendingTimeoutTask) {
        if (!pendingTimeoutTaskUpdater.compareAndSet(this, null, pendingTimeoutTask)) {
            for (;;) {
                final Runnable pendingTask = this.pendingTimeoutTask;
                final Runnable newPendingTask = () -> {
                    pendingTask.run();
                    pendingTimeoutTask.run();
                };
                if (pendingTimeoutTaskUpdater.compareAndSet(this, pendingTask, newPendingTask)) {
                    break;
                }
            }
        }
    }

    private void setPendingTimeoutNanos(long pendingTimeoutNanos) {
        for (;;) {
            final long oldPendingTimeoutNanos = this.pendingTimeoutNanos;
            if (pendingTimeoutNanosUpdater.compareAndSet(this, oldPendingTimeoutNanos,
                                                         pendingTimeoutNanos)) {
                break;
            }
        }
    }

    private void addPendingTimeoutNanos(long pendingTimeoutNanos) {
        for (;;) {
            final long oldPendingTimeoutNanos = this.pendingTimeoutNanos;
            final long newPendingTimeoutNanos =
                    LongMath.saturatedAdd(oldPendingTimeoutNanos, pendingTimeoutNanos);
            if (pendingTimeoutNanosUpdater.compareAndSet(this, oldPendingTimeoutNanos,
                                                         newPendingTimeoutNanos)) {
                break;
            }
        }
    }

    private void invokeTimeoutTask() {
        if (timeoutTask != null) {
            if (!whenTimingOutUpdater.compareAndSet(this, null, COMPLETED_FUTURE)) {
                if (timeoutTask.canSchedule()) {
                    whenTimingOut.doComplete();
                }
            }

            // Set TIMED_OUT flag first to prevent duplicate execution
            state = State.TIMED_OUT;

            // The returned value of `canSchedule()` could've been changed by the callbacks of `whenTimingOut`
            if (timeoutTask.canSchedule()) {
                timeoutTask.run();
            }

            if (!whenTimedOutUpdater.compareAndSet(this, null, COMPLETED_FUTURE)) {
                whenTimedOut.doComplete();
            }
        }
    }

    public boolean isTimedOut() {
        return state == State.TIMED_OUT;
    }

    public long timeoutNanos() {
        return initialized ? timeoutNanos : pendingTimeoutNanos;
    }

    public CompletableFuture<Void> whenTimingOut() {
        if (whenTimingOut != null) {
            return whenTimingOut;
        }
        whenTimingOutUpdater.compareAndSet(this, null, new TimeoutFuture());
        return whenTimingOut;
    }

    public CompletableFuture<Void> whenTimedOut() {
        if (whenTimedOut != null) {
            return whenTimedOut;
        }
        whenTimedOutUpdater.compareAndSet(this, null, new TimeoutFuture());
        return whenTimedOut;
    }

    private void ensureInitialized() {
        checkState(timeoutTask != null,
                   "init(eventLoop, timeoutTask) is not called yet.");
        if (state == State.INIT) {
            state = State.INACTIVE;
            firstExecutionTimeNanos = System.nanoTime();
        }
    }

    public Long startTimeNanos() {
        return state != State.INIT ? firstExecutionTimeNanos : null;
    }

    @Nullable
    @VisibleForTesting
    ScheduledFuture<?> timeoutFuture() {
        return timeoutFuture;
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

    private static class TimeoutFuture extends UnmodifiableFuture<Void> {
        void doComplete() {
            doComplete(null);
        }
    }
}
