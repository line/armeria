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
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
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
public final class CancellationScheduler {

    private static final AtomicReferenceFieldUpdater<CancellationScheduler, CancellationFuture>
            whenCancellingUpdater = AtomicReferenceFieldUpdater.newUpdater(
            CancellationScheduler.class, CancellationFuture.class, "whenCancelling");

    private static final AtomicReferenceFieldUpdater<CancellationScheduler, CancellationFuture>
            whenCancelledUpdater = AtomicReferenceFieldUpdater.newUpdater(
            CancellationScheduler.class, CancellationFuture.class, "whenCancelled");

    private static final AtomicReferenceFieldUpdater<CancellationScheduler, TimeoutFuture>
            whenTimingOutUpdater = AtomicReferenceFieldUpdater.newUpdater(
            CancellationScheduler.class, TimeoutFuture.class, "whenTimingOut");

    private static final AtomicReferenceFieldUpdater<CancellationScheduler, TimeoutFuture>
            whenTimedOutUpdater = AtomicReferenceFieldUpdater.newUpdater(
            CancellationScheduler.class, TimeoutFuture.class, "whenTimedOut");

    private static final AtomicReferenceFieldUpdater<CancellationScheduler, Runnable>
            pendingTaskUpdater = AtomicReferenceFieldUpdater.newUpdater(
            CancellationScheduler.class, Runnable.class, "pendingTask");

    private static final AtomicLongFieldUpdater<CancellationScheduler> pendingTimeoutNanosUpdater =
            AtomicLongFieldUpdater.newUpdater(CancellationScheduler.class, "pendingTimeoutNanos");

    private static final Runnable noopPendingTask = () -> {
    };

    private State state = State.INIT;
    private long timeoutNanos;
    private long startTimeNanos;
    @Nullable
    private EventExecutor eventLoop;
    @Nullable
    private CancellationTask task;
    @Nullable
    private volatile Runnable pendingTask;
    @Nullable
    private ScheduledFuture<?> scheduledFuture;
    @Nullable
    private volatile CancellationFuture whenCancelling;
    @Nullable
    private volatile CancellationFuture whenCancelled;
    @Nullable
    private volatile TimeoutFuture whenTimingOut;
    @Nullable
    private volatile TimeoutFuture whenTimedOut;
    @SuppressWarnings("FieldMayBeFinal")
    private volatile long pendingTimeoutNanos;
    @Nullable
    private Throwable initialCause;
    @Nullable
    private Throwable cause;

    public CancellationScheduler(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
        pendingTimeoutNanos = timeoutNanos;
    }

    /**
     * Initializes this {@link CancellationScheduler}.
     */
    public void init(EventExecutor eventLoop, CancellationTask task, long timeoutNanos, Throwable cause) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> init(eventLoop, task, timeoutNanos, cause));
            return;
        }
        if (state != State.INIT) {
            return;
        }
        this.eventLoop = eventLoop;
        this.task = task;
        if (timeoutNanos > 0) {
            this.timeoutNanos = timeoutNanos;
        }
        initialCause = cause;
        startTimeNanos = System.nanoTime();
        if (this.timeoutNanos != 0) {
            state = State.SCHEDULED;
            scheduledFuture =
                    eventLoop.schedule(() -> invokeTask(initialCause), this.timeoutNanos, NANOSECONDS);
        } else {
            state = State.INACTIVE;
        }
        for (;;) {
            final Runnable pendingTask = this.pendingTask;
            if (pendingTaskUpdater.compareAndSet(this, pendingTask, noopPendingTask)) {
                if (pendingTask != null) {
                    pendingTask.run();
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
            addPendingTask(() -> clearTimeout0(resetTimeout));
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
        assert scheduledFuture != null;
        final boolean cancelled = scheduledFuture.cancel(false);
        scheduledFuture = null;
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
            addPendingTask(() -> setTimeoutNanosFromStart0(timeoutNanos));
        }
    }

    private void setTimeoutNanosFromStart0(long timeoutNanos) {
        assert eventLoop != null && eventLoop.inEventLoop() && initialCause != null;
        final long passedTimeNanos = System.nanoTime() - startTimeNanos;
        final long newTimeoutNanos = LongMath.saturatedSubtract(timeoutNanos, passedTimeNanos);
        if (newTimeoutNanos <= 0) {
            invokeTask(initialCause);
            return;
        }
        // Cancel the previously scheduled timeout, if exists.
        clearTimeout0(true);
        this.timeoutNanos = timeoutNanos;
        state = State.SCHEDULED;
        scheduledFuture = eventLoop.schedule(() -> invokeTask(initialCause), newTimeoutNanos, NANOSECONDS);
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
            addPendingTask(() -> extendTimeoutNanos0(adjustmentNanos));
        }
    }

    private void extendTimeoutNanos0(long adjustmentNanos) {
        assert eventLoop != null && eventLoop.inEventLoop() && task != null && initialCause != null;
        if (state != State.SCHEDULED || !task.canSchedule()) {
            return;
        }
        final long timeoutNanos = this.timeoutNanos;
        // Cancel the previously scheduled timeout, if exists.
        clearTimeout0(true);
        this.timeoutNanos = LongMath.saturatedAdd(timeoutNanos, adjustmentNanos);
        if (timeoutNanos <= 0) {
            invokeTask(initialCause);
            return;
        }
        state = State.SCHEDULED;
        scheduledFuture = eventLoop.schedule(() -> invokeTask(initialCause), this.timeoutNanos, NANOSECONDS);
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
            addPendingTask(() -> {
                final long passedTimeNanos0 = System.nanoTime() - startTimeNanos;
                final long timeoutNanos0 = Math.max(1, timeoutNanos - passedTimeNanos0);
                setTimeoutNanosFromNow0(timeoutNanos0);
            });
        }
    }

    private void setTimeoutNanosFromNow0(long newTimeoutNanos) {
        assert eventLoop != null && eventLoop.inEventLoop() && task != null && initialCause != null;
        if (state == State.FINISHED || !task.canSchedule()) {
            return;
        }
        // Cancel the previously scheduled timeout, if exists.
        clearTimeout0(true);
        final long passedTimeNanos = System.nanoTime() - startTimeNanos;
        timeoutNanos = LongMath.saturatedAdd(newTimeoutNanos, passedTimeNanos);
        if (newTimeoutNanos <= 0) {
            return;
        }
        state = State.SCHEDULED;
        scheduledFuture = eventLoop.schedule(() -> invokeTask(initialCause), newTimeoutNanos, NANOSECONDS);
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
            addPendingTask(() -> finishNow0(cause));
        }
    }

    private void finishNow0(Throwable cause) {
        assert eventLoop != null && eventLoop.inEventLoop() && task != null;
        if (state == State.FINISHED || !task.canSchedule()) {
            return;
        }
        if (state == State.SCHEDULED) {
            if (clearTimeout0(false)) {
                invokeTask(cause);
            }
        } else {
            invokeTask(cause);
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

    public long startTimeNanos() {
        return startTimeNanos;
    }

    public CompletableFuture<Throwable> whenCancelling() {
        final CancellationFuture whenCancelling = this.whenCancelling;
        if (whenCancelling != null) {
            return whenCancelling;
        }
        final CancellationFuture cancellationFuture = new CancellationFuture();
        if (whenCancellingUpdater.compareAndSet(this, null, cancellationFuture)) {
            return cancellationFuture;
        } else {
            return this.whenCancelling;
        }
    }

    public CompletableFuture<Throwable> whenCancelled() {
        final CancellationFuture whenCancelled = this.whenCancelled;
        if (whenCancelled != null) {
            return whenCancelled;
        }
        final CancellationFuture cancellationFuture = new CancellationFuture();
        if (whenCancelledUpdater.compareAndSet(this, null, cancellationFuture)) {
            return cancellationFuture;
        } else {
            return this.whenCancelled;
        }
    }

    @Deprecated
    public CompletableFuture<Void> whenTimingOut() {
        final TimeoutFuture whenTimingOut = this.whenTimingOut;
        if (whenTimingOut != null) {
            return whenTimingOut;
        }
        final TimeoutFuture timeoutFuture = new TimeoutFuture();
        if (whenTimingOutUpdater.compareAndSet(this, null, timeoutFuture)) {
            whenCancelling().thenAccept(cause -> {
                if (cause instanceof TimeoutException) {
                    timeoutFuture.doComplete();
                }
            });
            return timeoutFuture;
        } else {
            return this.whenTimingOut;
        }
    }

    @Deprecated
    public CompletableFuture<Void> whenTimedOut() {
        final TimeoutFuture whenTimedOut = this.whenTimedOut;
        if (whenTimedOut != null) {
            return whenTimedOut;
        }
        final TimeoutFuture timeoutFuture = new TimeoutFuture();
        if (whenTimedOutUpdater.compareAndSet(this, null, timeoutFuture)) {
            whenCancelled().thenAccept(cause -> {
                if (cause instanceof TimeoutException) {
                    timeoutFuture.doComplete();
                }
            });
            return timeoutFuture;
        } else {
            return this.whenTimedOut;
        }
    }

    private boolean isInitialized() {
        return pendingTask == noopPendingTask && eventLoop != null;
    }

    private void addPendingTask(Runnable pendingTask) {
        if (!pendingTaskUpdater.compareAndSet(this, null, pendingTask)) {
            for (;;) {
                final Runnable oldPendingTask = this.pendingTask;
                assert oldPendingTask != null;
                if (oldPendingTask == noopPendingTask) {
                    assert eventLoop != null;
                    eventLoop.execute(pendingTask);
                    break;
                }
                final Runnable newPendingTask = () -> {
                    oldPendingTask.run();
                    pendingTask.run();
                };
                if (pendingTaskUpdater.compareAndSet(this, oldPendingTask, newPendingTask)) {
                    break;
                }
            }
        }
    }

    private void setPendingTimeoutNanos(long pendingTimeoutNanos) {
        for (;;) {
            final long oldPendingTimeoutNanos = this.pendingTimeoutNanos;
            if (pendingTimeoutNanosUpdater.compareAndSet(this, oldPendingTimeoutNanos, pendingTimeoutNanos)) {
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

    private void invokeTask(Throwable cause) {
        if (task == null) {
            return;
        }
        if (task.canSchedule()) {
            ((CancellationFuture) whenCancelling()).doComplete(cause);
        }
        // Set state first to prevent duplicate execution
        state = State.FINISHED;
        // The returned value of `canSchedule()` could've been changed by the callbacks of `whenCancelling`
        if (task.canSchedule()) {
            task.run(cause);
        }
        this.cause = cause;
        ((CancellationFuture) whenCancelled()).doComplete(cause);
    }

    @Nullable
    @VisibleForTesting
    ScheduledFuture<?> scheduledFuture() {
        return scheduledFuture;
    }

    @VisibleForTesting
    State state() {
        return state;
    }

    enum State {
        INIT,
        INACTIVE,
        SCHEDULED,
        FINISHED
    }

    /**
     * A cancellation task invoked by the scheduler when its timeout exceeds or invoke by the user.
     */
    public interface CancellationTask {
        /**
         * Returns {@code true} if the cancellation task can be scheduled.
         */
        boolean canSchedule();

        /**
         * Invoked by the scheduler with the cause of cancellation.
         */
        void run(Throwable cause);
    }

    private static class CancellationFuture extends UnmodifiableFuture<Throwable> {
        protected void doComplete(@Nullable Throwable cause) {
            super.doComplete(cause);
        }
    }

    private static class TimeoutFuture extends UnmodifiableFuture<Void> {
        void doComplete() {
            doComplete(null);
        }
    }
}
