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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.concurrent.EventExecutor;

@SuppressWarnings("UnstableApiUsage")
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

    private static final Runnable initializedPendingTimeoutTask = () -> {};

    private static final TimeoutFuture COMPLETED_FUTURE;
    private static final TimeoutFuture CANCELLED_FUTURE;

    static {
        COMPLETED_FUTURE = new TimeoutFuture();
        COMPLETED_FUTURE.doComplete();
        CANCELLED_FUTURE = new TimeoutFuture();
        CANCELLED_FUTURE.cancel(true);
    }

    enum State {
        INIT,
        INACTIVE,
        SCHEDULED,
        FINISHED
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
    @Nullable
    private Throwable initialCause;
    @Nullable
    private Throwable cause;

    public TimeoutScheduler(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
        pendingTimeoutNanos = timeoutNanos;
    }

    /**
     * Initializes this {@link TimeoutScheduler}.
     */
    public void init(EventExecutor eventLoop, TimeoutTask timeoutTask, long initialTimeoutNanos,
                     Throwable cause) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> init(eventLoop, timeoutTask, initialTimeoutNanos, cause));
            return;
        }

        if (state != State.INIT) {
            return;
        }

        this.eventLoop = eventLoop;
        this.timeoutTask = timeoutTask;
        if (initialTimeoutNanos > 0) {
            timeoutNanos = initialTimeoutNanos;
        }
        initialCause = cause;
        firstExecutionTimeNanos = System.nanoTime();

        if (timeoutNanos != 0) {
            state = State.SCHEDULED;
            timeoutFuture = eventLoop.schedule(() -> invokeTimeoutTask(initialCause), timeoutNanos,
                                               TimeUnit.NANOSECONDS);
        } else {
            state = State.INACTIVE;
        }

        for (;;) {
            final Runnable pendingTimeoutTask = this.pendingTimeoutTask;
            if (pendingTimeoutTaskUpdater.compareAndSet(this, pendingTimeoutTask,
                                                        initializedPendingTimeoutTask)) {
                if (pendingTimeoutTask != null) {
                    pendingTimeoutTask.run();
                }
                break;
            }
        }
    }

    public void clearTimeout() {
       clearTimeout(true);
    }

    public void clearTimeout(boolean resetTimeout) {
        if (timeoutNanos() == 0) {
            return;
        }

        if (isInitialized()) {
            if (eventLoop.inEventLoop()) {
                clearTimeout0(resetTimeout);
            } else {
                eventLoop.execute(() -> clearTimeout0(resetTimeout));
            }
        } else {
            if (resetTimeout) {
                setPendingTimeoutNanos(0);
            }
            addPendingTimeoutTask(() -> clearTimeout0(resetTimeout));
        }
    }

    private boolean clearTimeout0(boolean resetTimeout) {
        assert eventLoop != null && eventLoop.inEventLoop();

        if (state != State.SCHEDULED) {
            return true;
        }

        if (resetTimeout) {
            timeoutNanos = 0;
        }

        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        assert timeoutFuture != null;

        final boolean cancelled = timeoutFuture.cancel(false);
        this.timeoutFuture = null;
        if (cancelled) {
            state = State.INACTIVE;
        }
        return cancelled;
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

        if (isInitialized()) {
            if (eventLoop.inEventLoop()) {
                setTimeoutNanosFromStart0(timeoutNanos);
            } else {
                eventLoop.execute(() -> setTimeoutNanosFromStart0(timeoutNanos));
            }
        } else {
            addPendingTimeoutNanos(timeoutNanos);
            addPendingTimeoutTask(() -> setTimeoutNanosFromStart0(timeoutNanos));
        }
    }

    private void setTimeoutNanosFromStart0(long timeoutNanos) {
        assert eventLoop != null && eventLoop.inEventLoop() && initialCause != null;

        final long passedTimeNanos = System.nanoTime() - firstExecutionTimeNanos;
        final long newTimeoutNanos = LongMath.saturatedSubtract(timeoutNanos, passedTimeNanos);

        if (newTimeoutNanos <= 0) {
            invokeTimeoutTask(initialCause);
            return;
        }

        // Cancel the previously scheduled timeout, if exists.
        clearTimeout0(true);
        this.timeoutNanos = timeoutNanos;

        state = State.SCHEDULED;
        timeoutFuture = eventLoop.schedule(() -> invokeTimeoutTask(initialCause), newTimeoutNanos,
                                           TimeUnit.NANOSECONDS);
    }

    private void extendTimeoutNanos(long adjustmentNanos) {
        if (adjustmentNanos == 0 || timeoutNanos() == 0) {
            return;
        }

        if (isInitialized()) {
            if (eventLoop.inEventLoop()) {
                extendTimeoutNanos0(adjustmentNanos);
            } else {
                eventLoop.execute(() -> extendTimeoutNanos0(adjustmentNanos));
            }
        } else {
            addPendingTimeoutNanos(adjustmentNanos);
            addPendingTimeoutTask(() -> extendTimeoutNanos0(adjustmentNanos));
        }
    }

    private void extendTimeoutNanos0(long adjustmentNanos) {
        assert eventLoop != null && eventLoop.inEventLoop() && timeoutTask != null && initialCause != null;

        if (state != State.SCHEDULED || !timeoutTask.canSchedule()) {
            return;
        }

        final long timeoutNanos = this.timeoutNanos;
        // Cancel the previously scheduled timeout, if exists.
        clearTimeout0(true);

        this.timeoutNanos = LongMath.saturatedAdd(timeoutNanos, adjustmentNanos);

        if (timeoutNanos <= 0) {
            invokeTimeoutTask(initialCause);
            return;
        }

        state = State.SCHEDULED;
        timeoutFuture = eventLoop.schedule(() -> invokeTimeoutTask(initialCause), this.timeoutNanos,
                                           TimeUnit.NANOSECONDS);
    }

    private void setTimeoutNanosFromNow(long timeoutNanos) {
        checkArgument(timeoutNanos > 0, "timeoutNanos: %s (expected: > 0)", timeoutNanos);

        if (isInitialized()) {
            if (eventLoop.inEventLoop()) {
                setTimeoutNanosFromNow0(timeoutNanos);
            } else {
                eventLoop.execute(() -> setTimeoutNanosFromNow0(timeoutNanos));
            }
        } else {
            final long startTimeNanos = System.nanoTime();
            setPendingTimeoutNanos(timeoutNanos);
            addPendingTimeoutTask(() -> {
                final long passedTimeNanos0 = System.nanoTime() - startTimeNanos;
                final long timeoutNanos0 = Math.max(1, timeoutNanos - passedTimeNanos0);
                setTimeoutNanosFromNow0(timeoutNanos0);
            });
        }
    }

    private void setTimeoutNanosFromNow0(long newTimeoutNanos) {
        assert eventLoop != null && eventLoop.inEventLoop() && timeoutTask != null && initialCause != null;

        if (state == State.FINISHED || !timeoutTask.canSchedule()) {
            return;
        }

        // Cancel the previously scheduled timeout, if exists.
        clearTimeout0(true);

        final long passedTimeNanos = System.nanoTime() - firstExecutionTimeNanos;
        timeoutNanos = LongMath.saturatedAdd(newTimeoutNanos, passedTimeNanos);

        if (newTimeoutNanos <= 0) {
            return;
        }

        state = State.SCHEDULED;
        timeoutFuture = eventLoop.schedule(() -> invokeTimeoutTask(initialCause), newTimeoutNanos,
                                           TimeUnit.NANOSECONDS);
    }

    public void finishNow() {
        assert initialCause != null;
        finishNow(initialCause);
    }

    public void finishNow(Throwable cause) {
        if (isInitialized()) {
            if (eventLoop.inEventLoop()) {
                finishNow0(cause);
            } else {
                eventLoop.execute(() -> finishNow0(cause));
            }
        } else {
            addPendingTimeoutTask(() -> finishNow0(cause));
        }
    }

    private void finishNow0(Throwable cause) {
        assert eventLoop != null && eventLoop.inEventLoop() && timeoutTask != null;

        if (!timeoutTask.canSchedule()) {
            return;
        }

        switch (state) {
            case FINISHED:
                return;
            case INIT:
            case INACTIVE:
                invokeTimeoutTask(cause);
                return;
            case SCHEDULED:
                if (clearTimeout0(false)) {
                    invokeTimeoutTask(cause);
                }
                return;
            default:
                throw new Error(); // Should not reach here.
        }
    }

    private void addPendingTimeoutTask(Runnable pendingTimeoutTask) {
        if (!pendingTimeoutTaskUpdater.compareAndSet(this, null, pendingTimeoutTask)) {
            for (;;) {
                final Runnable oldPendingTimeoutTask = this.pendingTimeoutTask;
                assert oldPendingTimeoutTask != null;

                if (oldPendingTimeoutTask == initializedPendingTimeoutTask) {
                    assert eventLoop != null;
                    eventLoop.execute(pendingTimeoutTask);
                    break;
                }

                final Runnable newPendingTimeoutTask = () -> {
                    oldPendingTimeoutTask.run();
                    pendingTimeoutTask.run();
                };

                if (pendingTimeoutTaskUpdater.compareAndSet(this, oldPendingTimeoutTask,
                                                            newPendingTimeoutTask)) {
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
            final long newPendingTimeoutNanos = LongMath.saturatedAdd(oldPendingTimeoutNanos,
                                                                      pendingTimeoutNanos);
            if (pendingTimeoutNanosUpdater.compareAndSet(this, oldPendingTimeoutNanos,
                                                         newPendingTimeoutNanos)) {
                break;
            }
        }
    }

    private void invokeTimeoutTask(Throwable cause) {
        if (timeoutTask == null) {
            return;
        }

        final boolean timingOut = cause instanceof TimeoutException;

        if (timingOut) {
            if (!whenTimingOutUpdater.compareAndSet(this, null, COMPLETED_FUTURE)) {
                if (timeoutTask.canSchedule()) {
                    whenTimingOut.doComplete();
                }
            }

            // Set state first to prevent duplicate execution
            state = State.FINISHED;
            // The returned value of `canSchedule()` could've been changed by the callbacks of `whenTimingOut`
            if (timeoutTask.canSchedule()) {
                timeoutTask.run(cause);
            }
            this.cause = cause;

            if (!whenTimedOutUpdater.compareAndSet(this, null, COMPLETED_FUTURE)) {
                whenTimedOut.doComplete();
            }
        } else {
            if (!whenTimingOutUpdater.compareAndSet(this, null, CANCELLED_FUTURE)) {
                whenTimingOut.doCancel();
            }

            // Set state first to prevent duplicate execution
            state = State.FINISHED;
            timeoutTask.run(cause);
            this.cause = cause;

            if (!whenTimedOutUpdater.compareAndSet(this, null, CANCELLED_FUTURE)) {
                whenTimedOut.doCancel();
            }
        }
    }

    public boolean isFinished() {
        return state == State.FINISHED;
    }

    @Nullable
    public Throwable cause() {
        return cause;
    }

    public long timeoutNanos() {
        return isInitialized() ? timeoutNanos : pendingTimeoutNanos;
    }

    private boolean isInitialized() {
        return pendingTimeoutTask == initializedPendingTimeoutTask && eventLoop != null;
    }

    public CompletableFuture<Void> whenTimingOut() {
        final TimeoutFuture whenTimingOut = this.whenTimingOut;
        if (whenTimingOut != null) {
            return whenTimingOut;
        }

        final TimeoutFuture timeoutFuture = new TimeoutFuture();
        if (whenTimingOutUpdater.compareAndSet(this, null, timeoutFuture)) {
            return timeoutFuture;
        } else {
            return this.whenTimingOut;
        }
    }

    public CompletableFuture<Void> whenTimedOut() {
        final TimeoutFuture whenTimedOut = this.whenTimedOut;
        if (whenTimedOut != null) {
            return whenTimedOut;
        }

        final TimeoutFuture timeoutFuture = new TimeoutFuture();
        if (whenTimedOutUpdater.compareAndSet(this, null, timeoutFuture)) {
            return timeoutFuture;
        } else {
            return this.whenTimedOut;
        }
    }

    @Nullable
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
    public interface TimeoutTask {
        /**
         * Returns {@code true} if the timeout task can be scheduled.
         */
        boolean canSchedule();

        /**
         * Invoked when the deadline exceeded.
         */
        void run(Throwable cause);
    }

    private static class TimeoutFuture extends UnmodifiableFuture<Void> {
        void doComplete() {
            doComplete(null);
        }

        public boolean doCancel() {
            return super.doCancel();
        }
    }
}
