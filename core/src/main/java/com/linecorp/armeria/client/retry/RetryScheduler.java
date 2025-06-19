/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.linecorp.armeria.client.retry.RetrySchedulingException.Type;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class RetryScheduler {
    private static class RetryTaskHandle {
        enum CancellationReason {
            OVERTAKEN,
            RESCHEDULED,
            SHUTDOWN
        }

        private final Consumer<ReentrantLock> retryTask;
        private final ScheduledFuture<Void> scheduledFuture;
        private @Nullable CancellationReason cancellationReason;
        private final long retryTaskId;

        private final Consumer<@Nullable ? super Throwable> exceptionHandler;

        private final long retryTimeNanos;

        RetryTaskHandle(long retryTaskId, ScheduledFuture<Void> scheduledFuture,
                        Consumer<ReentrantLock> retryTask,
                        long retryTimeNanos,
                        Consumer<@Nullable ? super Throwable> exceptionHandler) {
            this.retryTaskId = retryTaskId;
            this.retryTask = retryTask;
            this.scheduledFuture = scheduledFuture;
            this.exceptionHandler = exceptionHandler;
            this.retryTimeNanos = retryTimeNanos;
            cancellationReason = null;
        }

        Consumer<ReentrantLock> getRetryTaskRunnable() {
            return retryTask;
        }

        long id() {
            return retryTaskId;
        }

        long retryTimeNanos() {
            return retryTimeNanos;
        }

        void setCancellationReason(CancellationReason cancellationReason) {
            checkState(this.cancellationReason == null, "cancellationReason");
            this.cancellationReason = cancellationReason;
        }

        @Nullable
        CancellationReason getCancellationReason() {
            return cancellationReason;
        }

        Consumer<? super Throwable> getExceptionHandler() {
            return exceptionHandler;
        }

        ScheduledFuture<Void> getFuture() {
            return scheduledFuture;
        }
    }

    // todo: should we make this a flag?
    // Number of nanoseconds that we allow the retry task to be scheduled earlier than
    // the earliestNextRetryTimeNanos.
    // This should avoid unnecessary rescheduling.
    private static final long RESCHEDULING_OVERTAKING_TOLERANCE_NANOS = TimeUnit.MICROSECONDS.toNanos(500);

    // for locking
    private final ReentrantLock retryLock;
    private final EventLoop eventLoop;
    private final long latestNextRetryTimeNanos;

    private long earliestNextRetryTimeNanos;
    private long retryTaskId;

    // The retry task that is about to be executed next.
    // It is possible that the delay of this task is shorter than the `earliestNextRetryTimeNanos`,
    // because of calls to `addEarliestNextRetryTimeNanos` with a pending call to `schedule` or
    // `rescheduleCurrentRetryTaskIfTooEarly`.
    private @Nullable RetryTaskHandle currentRetryTask;

    RetryScheduler(ReentrantLock retryLock, EventLoop eventLoop) {
        this(retryLock, eventLoop, Long.MAX_VALUE);
    }

    RetryScheduler(ReentrantLock retryLock, EventLoop eventLoop, long latestNextRetryTimeNanos) {
        this.retryLock = requireNonNull(retryLock, "retryLock");
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        currentRetryTask = null;
        earliestNextRetryTimeNanos = Long.MIN_VALUE;
        this.latestNextRetryTimeNanos = latestNextRetryTimeNanos;
        retryTaskId = 0;
    }

    public void schedule(Consumer<ReentrantLock> retryTask, long nextRetryTimeNanos,
                         long earliestNextRetryTimeNanos,
                         Consumer<? super Throwable> exceptionHandler) {
        requireNonNull(retryTask, "retryTask");
        checkArgument(nextRetryTimeNanos >= earliestNextRetryTimeNanos,
                      "nextRetryTimeNanos: %s (expected: >= %s)",
                      nextRetryTimeNanos, earliestNextRetryTimeNanos);
        requireNonNull(exceptionHandler, "exceptionHandler");

        retryLock.lock();

        addEarliestNextRetryTimeNanos(earliestNextRetryTimeNanos);
        nextRetryTimeNanos = Math.max(nextRetryTimeNanos, this.earliestNextRetryTimeNanos);

        // Math.max because we could have added an earliestNextRetryTimeNanos that is later than the
        // currently scheduled retry task (even not by us in this method).
        if (currentRetryTask == null || nextRetryTimeNanos < Math.max(this.earliestNextRetryTimeNanos,
                                                                      currentRetryTask.retryTimeNanos())) {
            // takes ownership of the acquired retry lock
            scheduleNextRetryTask(retryTask, nextRetryTimeNanos, exceptionHandler, false);
        } else {
            // Make sure the current retry task is not scheduled too early.
            // takes ownership of the acquired retry lock
            rescheduleCurrentRetryTaskIfTooEarly0();

            exceptionHandler.accept(new RetrySchedulingException(
                    RetrySchedulingException.Type.RETRY_TASK_OVERTAKEN));
        }
    }

    private boolean cancelCurrentRetryTask(RetryTaskHandle.CancellationReason cancellationReason) {
        retryLock.lock();

        try {
            if (currentRetryTask != null) {
                final ScheduledFuture<Void> retryTaskFuture = currentRetryTask.getFuture();

                currentRetryTask.setCancellationReason(cancellationReason);
                // This should not invoke the user-defined exception handler with our lock as
                // we marked the task accordingly.
                if (!retryTaskFuture.cancel(false)) {
                    return false;
                } else {
                    clearCurrentRetryTask();
                    return true;
                }
            }

            return true;
        } finally {
            retryLock.unlock();
        }
    }

    private void handleRetryTaskCompletion(RetryTaskHandle retryTaskHandle) {
        retryLock.lock();

        try {
            final ScheduledFuture<Void> retryTaskFuture = retryTaskHandle.getFuture();
            assert retryTaskFuture.isDone();

            if (currentRetryTask == retryTaskHandle) {
                clearCurrentRetryTask();
            }

            if (retryTaskFuture.isCancelled()) {
                final RetryTaskHandle.CancellationReason cancellationReason =
                        retryTaskHandle.getCancellationReason();

                if (cancellationReason == RetryTaskHandle.CancellationReason.RESCHEDULED) {
                    return;
                }

                if (cancellationReason == RetryTaskHandle.CancellationReason.OVERTAKEN) {
                    retryTaskHandle.getExceptionHandler().accept(
                            new RetrySchedulingException(Type.RETRY_TASK_OVERTAKEN)
                    );
                    return;
                }

                if (cancellationReason == RetryTaskHandle.CancellationReason.SHUTDOWN) {
                    retryTaskHandle.getExceptionHandler().accept(
                            new RetrySchedulingException(Type.RETRYING_ALREADY_COMPLETED)
                    );
                    return;
                }

                assert cancellationReason == null;

                // The retry task was cancelled by the user, not by the scheduler.
                retryTaskHandle.getExceptionHandler().accept(
                        new RetrySchedulingException(Type.RETRY_TASK_CANCELLED));
                return;
            }

            if (!retryTaskFuture.isSuccess()) {
                retryTaskHandle.getExceptionHandler().accept(retryTaskFuture.cause());
            }
        } finally {
            retryLock.unlock();
        }
    }

    private void clearCurrentRetryTask() {
        retryLock.lock();
        try {
            currentRetryTask = null;
            earliestNextRetryTimeNanos = Long.MIN_VALUE;
        } finally {
            retryLock.unlock();
        }
    }

    // takes ownership of the acquired retry lock
    private void scheduleNextRetryTask(Consumer<ReentrantLock> retryRunnable, long retryTimeNanos,
                                       Consumer<? super Throwable> exceptionHandler,
                                       boolean isReschedule) {

        assert retryLock.isHeldByCurrentThread();
        assert earliestNextRetryTimeNanos <= retryTimeNanos;

        if (!cancelCurrentRetryTask(
                isReschedule ?
                RetryTaskHandle.CancellationReason.RESCHEDULED
                             : RetryTaskHandle.CancellationReason.OVERTAKEN
        )) {
            retryLock.unlock();
            exceptionHandler.accept(new IllegalStateException("Current retry task could not be cancelled."));
            return;
        }

        assert currentRetryTask == null;

        try {
            final long nowNanos = System.nanoTime();
            final long delayNanos;
            if (retryTimeNanos <= nowNanos) {
                delayNanos = 0;
            } else {
                delayNanos = retryTimeNanos - nowNanos;
            }

            final long thisRetryId = retryTaskId++;

            final Runnable wrappedRetryRunnable = () -> {
                retryLock.lock();
                assert retryLock.getHoldCount() == 1;

                // Let be defensive here.
                if (currentRetryTask == null) {
                    clearCurrentRetryTask();
                    retryLock.unlock();
                    exceptionHandler.accept(new RetrySchedulingException(Type.RETRY_TASK_CANCELLED));
                    return;
                }

                if (currentRetryTask.id() != thisRetryId) {
                    retryLock.unlock();
                    exceptionHandler.accept(new RetrySchedulingException(Type.RETRY_TASK_CANCELLED));
                    return;
                }

                final long taskRunTimeNanos = System.nanoTime();

                // max to be robust against overflows.
                if (Math.max(taskRunTimeNanos, taskRunTimeNanos + RESCHEDULING_OVERTAKING_TOLERANCE_NANOS) <
                    earliestNextRetryTimeNanos) {

                    final Consumer<ReentrantLock> currentRetryRunnable =
                            currentRetryTask.getRetryTaskRunnable();
                    final Consumer<? super Throwable> currentExceptionHandler =
                            currentRetryTask.getExceptionHandler();

                    clearCurrentRetryTask();
                    // do not invoke rescheduleCurrentRetryTaskIfTooEarly here as it will not be able
                    // to cancel us.
                    // takes ownership of the acquired retry lock
                    scheduleNextRetryTask(currentRetryRunnable,
                                          earliestNextRetryTimeNanos,
                                          currentExceptionHandler, false);
                    return;
                }

                clearCurrentRetryTask();

                assert retryLock.isHeldByCurrentThread();
                assert retryLock.getHoldCount() == 1;

                // Run this with the lock. Expected to release the retry lock after consuming an attempt.
                retryRunnable.accept(retryLock);
            };

            //noinspection unchecked
            final ScheduledFuture<Void> nextRetryTaskFuture =
                    (ScheduledFuture<Void>) eventLoop.schedule(wrappedRetryRunnable, delayNanos,
                                                               TimeUnit.NANOSECONDS);

            // We are passing in the original to avoid multiple wrapping in case of the retry task being
            // rescheduled multiple times.
            final RetryTaskHandle nextRetryTask = new RetryTaskHandle(thisRetryId, nextRetryTaskFuture,
                                                                      retryRunnable,
                                                                      retryTimeNanos,
                                                                      exceptionHandler);

            nextRetryTaskFuture.addListener(f -> handleRetryTaskCompletion(nextRetryTask));
            currentRetryTask = nextRetryTask;
            retryLock.unlock();
        } catch (Throwable t) {
            retryLock.unlock();
            exceptionHandler.accept(t);
        }
    }

    public long addEarliestNextRetryTimeNanos(long earliestNextRetryTimeNanos) {
        retryLock.lock();

        try {
            checkState(earliestNextRetryTimeNanos <= latestNextRetryTimeNanos);
            this.earliestNextRetryTimeNanos = Math.max(this.earliestNextRetryTimeNanos,
                                                       earliestNextRetryTimeNanos);

            return this.earliestNextRetryTimeNanos;
        } finally {
            retryLock.unlock();
        }
    }

    // takes ownership of the acquired retry lock (if any)
    public void rescheduleCurrentRetryTaskIfTooEarly() {
        retryLock.lock();
        // takes ownership of the acquired retry lock
        rescheduleCurrentRetryTaskIfTooEarly0();
    }

    // takes ownership of the acquired retry lock
    private void rescheduleCurrentRetryTaskIfTooEarly0() {
        assert retryLock.isHeldByCurrentThread();

        if (currentRetryTask == null) {
            retryLock.unlock();
            return;
        }

        if (
                Math.max(currentRetryTask.retryTimeNanos(),
                         currentRetryTask.retryTimeNanos() + RESCHEDULING_OVERTAKING_TOLERANCE_NANOS) <
                earliestNextRetryTimeNanos
        ) {
            // Current retry task is going to be executed before the earliestNextRetryTimeNanos so
            // we need to reschedule it.

            // Takes ownership of the acquired lock.
            scheduleNextRetryTask(currentRetryTask.getRetryTaskRunnable(),
                                  earliestNextRetryTimeNanos,
                                  currentRetryTask.getExceptionHandler(), true);
        } else {
            retryLock.unlock();
        }
    }

    public boolean shutdown() {
        retryLock.lock();
        try {
            return cancelCurrentRetryTask(RetryTaskHandle.CancellationReason.SHUTDOWN);
        } finally {
            retryLock.unlock();
        }
    }
}
