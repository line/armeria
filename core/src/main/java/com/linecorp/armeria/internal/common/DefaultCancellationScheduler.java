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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.math.LongMath;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Ticker;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

final class DefaultCancellationScheduler implements CancellationScheduler {

    static final CancellationScheduler serverFinishedCancellationScheduler = finished0(true);
    static final CancellationScheduler clientFinishedCancellationScheduler = finished0(false);

    private State state = State.INIT;
    private long timeoutNanos;
    private long startTimeNanos;
    @Nullable
    private EventExecutor eventLoop;
    private volatile CancellationTask task = noopCancellationTask;
    @Nullable
    private ScheduledFuture<?> scheduledFuture;
    private long setFromNowStartNanos;
    private TimeoutMode timeoutMode = TimeoutMode.SET_FROM_START;
    @Nullable
    private volatile Throwable cause;
    private final Ticker ticker;
    private final ReentrantShortLock lock = new ReentrantShortLock();

    private final boolean server;
    private final CancellationFuture whenCancelling = new CancellationFuture();
    private final CancellationFuture whenCancelled = new CancellationFuture();

    @VisibleForTesting
    DefaultCancellationScheduler(long timeoutNanos) {
        this(timeoutNanos, true);
    }

    DefaultCancellationScheduler(long timeoutNanos, boolean server) {
        this(timeoutNanos, server, Ticker.systemTicker());
    }

    @VisibleForTesting
    DefaultCancellationScheduler(long timeoutNanos, boolean server, Ticker ticker) {
        this.timeoutNanos = timeoutNanos;
        this.server = server;
        this.ticker = ticker;
    }

    /**
     * Initializes this {@link DefaultCancellationScheduler}.
     */
    @Override
    public void initAndStart(EventExecutor eventLoop, CancellationTask task) {
        lock.lock();
        try {
            init(eventLoop);
            updateTask(task);
            start();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void init(EventExecutor eventLoop) {
        lock.lock();
        try {
            checkState(this.eventLoop == null, "Can't init() more than once");
            this.eventLoop = eventLoop;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void start() {
        lock.lock();
        try {
            if (state != State.INIT) {
                return;
            }
            state = State.SCHEDULED;
            startTimeNanos = ticker.read();
            if (timeoutMode == TimeoutMode.SET_FROM_NOW) {
                final long elapsedTimeNanos = startTimeNanos - setFromNowStartNanos;
                timeoutNanos = Long.max(LongMath.saturatedSubtract(timeoutNanos, elapsedTimeNanos), 0);
            }
            if (timeoutNanos != Long.MAX_VALUE) {
                scheduledFuture = eventLoop().schedule(() -> invokeTask(null), timeoutNanos, NANOSECONDS);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clearTimeout() {
        lock.lock();
        try {
            if (timeoutNanos == Long.MAX_VALUE) {
                return;
            }
            timeoutNanos = Long.MAX_VALUE;
            if (isStarted()) {
                cancelScheduled();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean cancelScheduled() {
        lock.lock();
        try {
            if (scheduledFuture == null) {
                return true;
            }
            final boolean cancelled = scheduledFuture.cancel(false);
            scheduledFuture = null;
            return cancelled;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isScheduled() {
        return scheduledFuture != null;
    }

    @Override
    public void setTimeoutNanos(TimeoutMode mode, long timeoutNanos) {
        lock.lock();
        final ScheduleResult result;
        try {
            switch (mode) {
                case SET_FROM_NOW:
                    result = setTimeoutNanosFromNow(timeoutNanos);
                    break;
                case SET_FROM_START:
                    result = setTimeoutNanosFromStart(timeoutNanos);
                    break;
                case EXTEND:
                    result = extendTimeoutNanos(timeoutNanos);
                    break;
                default:
                    throw new Error();
            }
        } finally {
            lock.unlock();
        }
        if (result == ScheduleResult.INVOKE_IMMEDIATELY) {
            invokeTask(null);
        }
    }

    private ScheduleResult setTimeoutNanosFromStart(long timeoutNanos) {
        checkArgument(timeoutNanos >= 0, "timeoutNanos: %s (expected: >= 0)", timeoutNanos);
        if (timeoutNanos == Long.MAX_VALUE) {
            clearTimeout();
            return ScheduleResult.INVOKE_LATER;
        }
        if (isStarted()) {
            return setTimeoutNanosFromStart0(timeoutNanos);
        }
        this.timeoutNanos = timeoutNanos;
        timeoutMode = TimeoutMode.SET_FROM_START;
        return ScheduleResult.INVOKE_LATER;
    }

    private ScheduleResult setTimeoutNanosFromStart0(long timeoutNanos) {
        final long newTimeoutNanos;
        if (timeoutNanos != Long.MAX_VALUE) {
            final long passedTimeNanos = ticker.read() - startTimeNanos;
            newTimeoutNanos = LongMath.saturatedSubtract(timeoutNanos, passedTimeNanos);
        } else {
            newTimeoutNanos = timeoutNanos;
        }

        timeoutMode = TimeoutMode.SET_FROM_START;
        this.timeoutNanos = timeoutNanos;
        if (newTimeoutNanos <= 0) {
            return ScheduleResult.INVOKE_IMMEDIATELY;
        }
        // Cancel the previously scheduled timeout, if exists.
        if (cancelScheduled() && !isFinished() && newTimeoutNanos != Long.MAX_VALUE) {
            scheduledFuture = eventLoop().schedule(() -> invokeTask(null), newTimeoutNanos, NANOSECONDS);
        }
        return ScheduleResult.INVOKE_LATER;
    }

    private ScheduleResult extendTimeoutNanos(long adjustmentNanos) {
        if (timeoutNanos == Long.MAX_VALUE || adjustmentNanos == Long.MAX_VALUE) {
            return ScheduleResult.INVOKE_LATER;
        }
        if (isStarted()) {
            return extendTimeoutNanos0(adjustmentNanos);
        }
        timeoutNanos = LongMath.saturatedAdd(timeoutNanos, adjustmentNanos);
        return ScheduleResult.INVOKE_LATER;
    }

    private ScheduleResult extendTimeoutNanos0(long adjustmentNanos) {
        final long timeoutNanos = this.timeoutNanos;
        this.timeoutNanos = LongMath.saturatedAdd(timeoutNanos, adjustmentNanos);

        if (this.timeoutNanos <= 0) {
            return ScheduleResult.INVOKE_IMMEDIATELY;
        }
        // Cancel the previously scheduled timeout, if exists.
        if (cancelScheduled() && !isFinished()) {
            scheduledFuture = eventLoop().schedule(() -> invokeTask(null), this.timeoutNanos, NANOSECONDS);
        }
        return ScheduleResult.INVOKE_LATER;
    }

    private ScheduleResult setTimeoutNanosFromNow(long timeoutNanos) {
        checkArgument(timeoutNanos > 0, "timeoutNanos: %s (expected: > 0)", timeoutNanos);
        if (isStarted()) {
            return setTimeoutNanosFromNow0(timeoutNanos);
        }
        setFromNowStartNanos = ticker.read();
        timeoutMode = TimeoutMode.SET_FROM_NOW;
        this.timeoutNanos = timeoutNanos;
        return ScheduleResult.INVOKE_LATER;
    }

    private ScheduleResult setTimeoutNanosFromNow0(long newTimeoutNanos) {
        assert newTimeoutNanos > 0;
        final long passedTimeNanos = ticker.read() - startTimeNanos;
        timeoutNanos = LongMath.saturatedAdd(newTimeoutNanos, passedTimeNanos);
        timeoutMode = TimeoutMode.SET_FROM_NOW;
        // Cancel the previously scheduled timeout, if exists.
        if (cancelScheduled() && !isFinished() && newTimeoutNanos != Long.MAX_VALUE) {
            scheduledFuture = eventLoop().schedule(() -> invokeTask(null), newTimeoutNanos, NANOSECONDS);
        }
        return ScheduleResult.INVOKE_LATER;
    }

    private EventExecutor eventLoop() {
        assert eventLoop != null;
        return eventLoop;
    }

    @Override
    public void finishNow(@Nullable Throwable cause) {
        invokeTask(cause);
    }

    @Override
    @Nullable
    public Throwable cause() {
        return cause;
    }

    @Override
    public long timeoutNanos() {
        return timeoutNanos == Long.MAX_VALUE ? 0 : timeoutNanos;
    }

    @Override
    public long startTimeNanos() {
        return startTimeNanos;
    }

    private boolean isStarted() {
        return state != State.INIT;
    }

    @Override
    public boolean isFinished() {
        return state == State.FINISHED;
    }

    @Override
    public void updateTask(CancellationTask task) {
        lock.lock();
        try {
            if (state != State.FINISHED) {
                // if the task hasn't been run yet
                this.task = task;
                return;
            }
        } finally {
            lock.unlock();
        }

        whenCancelled().thenAccept(cause -> {
            if (task.canSchedule()) {
                task.run(cause);
            }
        });
    }

    private void invokeTask(@Nullable Throwable cause) {
        lock.lock();
        try {
            if (state == State.FINISHED) {
                return;
            }
            state = State.FINISHED;
            cancelScheduled();
            // set the cause
            cause = getFinalCause(cause);
            this.cause = cause;
        } finally {
            lock.unlock();
        }

        if (task.canSchedule()) {
            ((CancellationFuture) whenCancelling()).doComplete(cause);
        }

        if (task.canSchedule()) {
            assert !lock.isHeldByCurrentThread() : "Currently locked by lock: [" + lock + "], with count: " +
                                                   lock.getHoldCount();
            task.run(cause);
        }

        ((CancellationFuture) whenCancelled()).doComplete(cause);
    }

    private Throwable getFinalCause(@Nullable Throwable cause) {
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
        return cause;
    }

    @VisibleForTesting
    State state() {
        return state;
    }

    @Override
    public CompletableFuture<Throwable> whenCancelling() {
        return whenCancelling;
    }

    @Override
    public CompletableFuture<Throwable> whenCancelled() {
        return whenCancelled;
    }

    private enum ScheduleResult {
        INVOKE_LATER,
        INVOKE_IMMEDIATELY,
    }

    private static class CancellationFuture extends UnmodifiableFuture<Throwable> {
        @Override
        protected void doComplete(@Nullable Throwable cause) {
            super.doComplete(cause);
        }
    }

    private static CancellationScheduler finished0(boolean server) {
        final CancellationScheduler cancellationScheduler =
                new DefaultCancellationScheduler(Long.MAX_VALUE, server);
        cancellationScheduler.initAndStart(ImmediateEventExecutor.INSTANCE, noopCancellationTask);
        cancellationScheduler.finishNow();
        return cancellationScheduler;
    }

    static long translateTimeoutNanos(long timeoutNanos) {
        if (timeoutNanos == Long.MAX_VALUE) {
            // If the user specified MAX_VALUE, then use MAX_VALUE-1 since MAX_VALUE means no scheduling
            timeoutNanos = Long.MAX_VALUE - 1;
        }
        if (timeoutNanos == 0) {
            // If the user specified 0, then use MAX_VALUE which means no scheduling
            timeoutNanos = Long.MAX_VALUE;
        }
        return timeoutNanos;
    }
}
