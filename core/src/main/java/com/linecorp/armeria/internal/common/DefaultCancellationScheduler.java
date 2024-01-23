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
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.math.LongMath;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

final class DefaultCancellationScheduler implements CancellationScheduler {

    private static final AtomicReferenceFieldUpdater<DefaultCancellationScheduler, CancellationFuture>
            whenCancellingUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultCancellationScheduler.class, CancellationFuture.class, "whenCancelling");

    private static final AtomicReferenceFieldUpdater<DefaultCancellationScheduler, CancellationFuture>
            whenCancelledUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultCancellationScheduler.class, CancellationFuture.class, "whenCancelled");

    private static final AtomicReferenceFieldUpdater<DefaultCancellationScheduler, TimeoutFuture>
            whenTimingOutUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultCancellationScheduler.class, TimeoutFuture.class, "whenTimingOut");

    private static final AtomicReferenceFieldUpdater<DefaultCancellationScheduler, TimeoutFuture>
            whenTimedOutUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultCancellationScheduler.class, TimeoutFuture.class, "whenTimedOut");

    private static final AtomicReferenceFieldUpdater<DefaultCancellationScheduler, Runnable>
            pendingTaskUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultCancellationScheduler.class, Runnable.class, "pendingTask");

    private static final AtomicLongFieldUpdater<DefaultCancellationScheduler> pendingTimeoutNanosUpdater =
            AtomicLongFieldUpdater.newUpdater(DefaultCancellationScheduler.class, "pendingTimeoutNanos");

    private static final Runnable noopPendingTask = () -> {
    };

    static final CancellationScheduler serverFinishedCancellationScheduler = finished0(true);
    static final CancellationScheduler clientFinishedCancellationScheduler = finished0(false);

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
    private final boolean server;
    @Nullable
    private Throwable cause;

    @VisibleForTesting
    DefaultCancellationScheduler(long timeoutNanos) {
        this(timeoutNanos, true);
    }

    DefaultCancellationScheduler(long timeoutNanos, boolean server) {
        this.timeoutNanos = timeoutNanos;
        pendingTimeoutNanos = timeoutNanos;
        this.server = server;
    }

    /**
     * Initializes this {@link DefaultCancellationScheduler}.
     */
    @Override
    public void initAndStart(EventExecutor eventLoop, CancellationTask task) {
        init(eventLoop);
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> start(task));
        } else {
            start(task);
        }
    }

    @Override
    public void init(EventExecutor eventLoop) {
        checkState(this.eventLoop == null, "Can't init() more than once");
        this.eventLoop = eventLoop;
    }

    @Override
    public void start(CancellationTask task) {
        assert eventLoop != null;
        assert eventLoop.inEventLoop();
        if (isFinished()) {
            assert cause != null;
            task.run(cause);
            return;
        }
        if (this.task != null) {
            // just replace the task
            this.task = task;
            return;
        }
        this.task = task;
        startTimeNanos = System.nanoTime();
        if (timeoutNanos != 0) {
            state = State.SCHEDULED;
            scheduledFuture =
                    eventLoop.schedule(() -> invokeTask(null), timeoutNanos, NANOSECONDS);
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

    @Override
    public void clearTimeout() {
        clearTimeout(true);
    }

    @Override
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

    @Override
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
            setPendingTimeoutNanos(timeoutNanos);
            addPendingTask(() -> setTimeoutNanosFromStart0(timeoutNanos));
        }
    }

    private void setTimeoutNanosFromStart0(long timeoutNanos) {
        assert eventLoop != null && eventLoop.inEventLoop();
        final long passedTimeNanos = System.nanoTime() - startTimeNanos;
        final long newTimeoutNanos = LongMath.saturatedSubtract(timeoutNanos, passedTimeNanos);
        if (newTimeoutNanos <= 0) {
            invokeTask(null);
            return;
        }
        // Cancel the previously scheduled timeout, if exists.
        clearTimeout0(true);
        this.timeoutNanos = timeoutNanos;
        state = State.SCHEDULED;
        scheduledFuture = eventLoop.schedule(() -> invokeTask(null), newTimeoutNanos, NANOSECONDS);
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
        assert eventLoop != null && eventLoop.inEventLoop() && task != null;
        if (state != State.SCHEDULED || !task.canSchedule()) {
            return;
        }
        final long timeoutNanos = this.timeoutNanos;
        // Cancel the previously scheduled timeout, if exists.
        clearTimeout0(true);
        this.timeoutNanos = LongMath.saturatedAdd(timeoutNanos, adjustmentNanos);
        if (timeoutNanos <= 0) {
            invokeTask(null);
            return;
        }
        state = State.SCHEDULED;
        scheduledFuture = eventLoop.schedule(() -> invokeTask(null), this.timeoutNanos, NANOSECONDS);
    }

    private void setTimeoutNanosFromNow(long timeoutNanos) {
        checkArgument(timeoutNanos > 0, "timeoutNanos: %s (expected: > 0)", timeoutNanos);
        if (isInitialized()) {
            if (eventLoop.inEventLoop()) {
                setTimeoutNanosFromNow0(timeoutNanos);
            } else {
                final long eventLoopStartTimeNanos = System.nanoTime();
                eventLoop.execute(() -> {
                    final long passedTimeNanos0 = System.nanoTime() - eventLoopStartTimeNanos;
                    final long timeoutNanos0 = Math.max(1, timeoutNanos - passedTimeNanos0);
                    setTimeoutNanosFromNow0(timeoutNanos0);
                });
            }
        } else {
            final long pendingTaskRegisterTimeNanos = System.nanoTime();
            setPendingTimeoutNanos(timeoutNanos);
            addPendingTask(() -> {
                final long passedTimeNanos0 = System.nanoTime() - pendingTaskRegisterTimeNanos;
                final long timeoutNanos0 = Math.max(1, timeoutNanos - passedTimeNanos0);
                setTimeoutNanosFromNow0(timeoutNanos0);
            });
        }
    }

    private void setTimeoutNanosFromNow0(long newTimeoutNanos) {
        assert newTimeoutNanos > 0;
        assert eventLoop != null && eventLoop.inEventLoop() && task != null;
        if (isFinishing() || !task.canSchedule()) {
            return;
        }
        // Cancel the previously scheduled timeout, if exists.
        clearTimeout0(true);
        final long passedTimeNanos = System.nanoTime() - startTimeNanos;
        timeoutNanos = LongMath.saturatedAdd(newTimeoutNanos, passedTimeNanos);

        state = State.SCHEDULED;
        scheduledFuture = eventLoop.schedule(() -> invokeTask(null), newTimeoutNanos, NANOSECONDS);
    }

    @Override
    public void finishNow() {
        finishNow(null);
    }

    @Override
    public void finishNow(@Nullable Throwable cause) {
        if (isFinishing()) {
            return;
        }
        assert eventLoop != null;
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> finishNow(cause));
            return;
        }
        if (isInitialized()) {
            finishNow0(cause);
        } else {
            start(noopCancellationTask);
            finishNow0(cause);
        }
    }

    private void finishNow0(@Nullable Throwable cause) {
        assert eventLoop != null && eventLoop.inEventLoop() && task != null;
        if (isFinishing() || !task.canSchedule()) {
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

    @Override
    public boolean isFinished() {
        return state == State.FINISHED;
    }

    private boolean isFinishing() {
        return state == State.FINISHED || state == State.FINISHING;
    }

    @Override
    @Nullable
    public Throwable cause() {
        return cause;
    }

    @Override
    public long timeoutNanos() {
        return isInitialized() ? timeoutNanos : pendingTimeoutNanos;
    }

    @Override
    public long startTimeNanos() {
        return startTimeNanos;
    }

    @Override
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

    @Override
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

    @Override
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

    @Override
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

    private void invokeTask(@Nullable Throwable cause) {
        if (task == null) {
            return;
        }

        if (cause instanceof HttpStatusException || cause instanceof HttpResponseException) {
            // Log the requestCause only when an Http{Status,Response}Exception was created with a cause.
            cause = cause.getCause();
        }

        if (cause == null) {
            if (server) {
                cause = RequestTimeoutException.get();
            } else {
                cause = ResponseTimeoutException.get();
            }
        }

        // Set FINISHING to preclude executing other timeout operations from the callbacks of `whenCancelling()`
        state = State.FINISHING;
        if (task.canSchedule()) {
            ((CancellationFuture) whenCancelling()).doComplete(cause);
        }
        // Set state first to prevent duplicate execution
        state = State.FINISHED;

        // The returned value of `canSchedule()` could've been changed by the callbacks of `whenCancelling()`
        if (task.canSchedule()) {
            task.run(cause);
        }
        this.cause = cause;
        ((CancellationFuture) whenCancelled()).doComplete(cause);
    }

    @VisibleForTesting
    State state() {
        return state;
    }

    private static class CancellationFuture extends UnmodifiableFuture<Throwable> {
        @Override
        protected void doComplete(@Nullable Throwable cause) {
            super.doComplete(cause);
        }
    }

    private static class TimeoutFuture extends UnmodifiableFuture<Void> {
        void doComplete() {
            doComplete(null);
        }
    }

    private static CancellationScheduler finished0(boolean server) {
        final CancellationScheduler cancellationScheduler = new DefaultCancellationScheduler(0, server);
        cancellationScheduler.initAndStart(ImmediateEventExecutor.INSTANCE, noopCancellationTask);
        cancellationScheduler.finishNow();
        return cancellationScheduler;
    }
}
