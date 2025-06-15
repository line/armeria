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
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.retry.RetrySchedulingException.Type;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class RetryScheduler {
    private static class RetryTaskHandle {
        enum State {
            SCHEDULED,
            OVERTAKEN,
            RESCHEDULED
        }

        private final Runnable retryTask;
        private final ScheduledFuture<Void> scheduledFuture;
        private State state;

        private final Consumer<@Nullable ? super Throwable> exceptionHandler;

        private final long retryTimeNanos;

        RetryTaskHandle(ScheduledFuture<Void> scheduledFuture, Runnable retryTask,
                        long retryTimeNanos,
                        Consumer<@Nullable ? super Throwable> exceptionHandler) {
            this.retryTask = retryTask;
            this.scheduledFuture = scheduledFuture;
            this.exceptionHandler = exceptionHandler;
            this.retryTimeNanos = retryTimeNanos;
            state = State.SCHEDULED;
        }

        Runnable getRetryTaskRunnable() {
            return retryTask;
        }

        long retryTimeNanos() {
            return retryTimeNanos;
        }

        void markRescheduled() {
            assert state == State.SCHEDULED;
            state = State.RESCHEDULED;
        }

        void markOvertaken() {
            assert state == State.SCHEDULED;
            state = State.OVERTAKEN;
        }

        boolean isRescheduled() {
            return state == State.RESCHEDULED;
        }

        boolean isOvertaken() {
            return state == State.OVERTAKEN;
        }

        Consumer<? super Throwable> getExceptionHandler() {
            return exceptionHandler;
        }

        ScheduledFuture<Void> getFuture() {
            return scheduledFuture;
        }
    }

    // todo(szymon) [Q]: make this configurable?
    // Number of nanoseconds that we allow the retry task to be scheduled earlier than
    // the earliestNextRetryTimeNanos.
    // This should avoid unnecessary rescheduling.
    private static final long RESCHEDULING_OVERTAKING_TOLERANCE_NANOS = TimeUnit.MICROSECONDS.toNanos(500);

    private static final Logger logger = LoggerFactory.getLogger(RetryScheduler.class);

    private final EventLoop eventLoop;
    private final long latestNextRetryTimeNanos;

    private long earliestNextRetryTimeNanos;

    // The retry task that is about to be executed next.
    // It is possible that the delay of this task is shorter than the `earliestNextRetryTimeNanos`,
    // because of calls to `addEarliestNextRetryTimeNanos` with a pending call to `schedule` or
    // `rescheduleCurrentRetryTaskIfTooEarly`.
    private @Nullable RetryTaskHandle currentRetryTask;

    RetryScheduler(EventLoop eventLoop) {
        this(eventLoop, Long.MAX_VALUE);
    }

    RetryScheduler(EventLoop eventLoop, long latestNextRetryTimeNanos) {
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        currentRetryTask = null;
        earliestNextRetryTimeNanos = Long.MIN_VALUE;
        this.latestNextRetryTimeNanos = latestNextRetryTimeNanos;
    }

    public synchronized void schedule(Runnable retryTask, long nextRetryTimeNanos,
                                      long earliestNextRetryTimeNanos,
                                      Consumer<? super Throwable> exceptionHandler) {
        requireNonNull(retryTask, "retryTask");
        checkArgument(nextRetryTimeNanos >= earliestNextRetryTimeNanos,
                      "nextRetryTimeNanos: %s (expected: >= %s)",
                      nextRetryTimeNanos, earliestNextRetryTimeNanos);
        requireNonNull(exceptionHandler, "exceptionHandler");

        addEarliestNextRetryTimeNanos(earliestNextRetryTimeNanos);
        nextRetryTimeNanos = Math.max(nextRetryTimeNanos, this.earliestNextRetryTimeNanos);

        // Math.max because we could have added an earliestNextRetryTimeNanos that is later than the
        // currently scheduled retry task (even not by us in this method).
        if (currentRetryTask == null || nextRetryTimeNanos < Math.max(this.earliestNextRetryTimeNanos,
                                                                      currentRetryTask.retryTimeNanos())) {
            scheduleNextRetryTask(retryTask, nextRetryTimeNanos, exceptionHandler, false);
        } else {
            // Make sure the current retry task is not scheduled too early.
            rescheduleCurrentRetryTaskIfTooEarly();

            exceptionHandler.accept(new RetrySchedulingException(
                    RetrySchedulingException.Type.RETRY_TASK_OVERTAKEN));
        }
    }

    private synchronized boolean cancelCurrentRetryTask(boolean cancelForRescheduling) {
        if (currentRetryTask != null) {
            final ScheduledFuture<Void> retryTaskFuture = currentRetryTask.getFuture();

            if (cancelForRescheduling) {
                currentRetryTask.markRescheduled();
            } else {
                currentRetryTask.markOvertaken();
            }
            if (!retryTaskFuture.cancel(false)) {
                return false;
            } else {
                clearCurrentRetryTask();
                return true;
            }
        }

        return true;
    }

    private synchronized void handleRetryTaskCompletion(RetryTaskHandle retryTaskHandle) {
        final ScheduledFuture<Void> retryTaskFuture = retryTaskHandle.getFuture();
        assert retryTaskFuture.isDone();

        if (currentRetryTask == retryTaskHandle) {
            clearCurrentRetryTask();
        }

        if (retryTaskFuture.isCancelled()) {
            if (retryTaskHandle.isRescheduled()) {
                return;
            }

            if (retryTaskHandle.isOvertaken()) {
                retryTaskHandle.getExceptionHandler().accept(
                        new RetrySchedulingException(Type.RETRY_TASK_OVERTAKEN)
                );
                return;
            }

            // The retry task was cancelled by the user, not by the scheduler.
            retryTaskHandle.getExceptionHandler().accept(
                    new RetrySchedulingException(Type.RETRY_TASK_CANCELLED));
            return;
        }

        if (!retryTaskFuture.isSuccess()) {
            retryTaskHandle.getExceptionHandler().accept(retryTaskFuture.cause());
        }
    }

    private synchronized void clearCurrentRetryTask() {
        currentRetryTask = null;
        earliestNextRetryTimeNanos = Long.MIN_VALUE;
    }

    private synchronized void scheduleNextRetryTask(Runnable retryRunnable, long retryTimeNanos,
                                                    Consumer<? super Throwable> exceptionHandler,
                                                    boolean isReschedule) {
        assert earliestNextRetryTimeNanos <= retryTimeNanos;

        if (!cancelCurrentRetryTask(isReschedule)) {
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

            final Runnable wrappedRetryRunnable = () -> {
                logger.debug("Retry task starting. Resetting...");
                // todo(szymon): do sanity check that we are clearing this task (very bad otherwise).

                synchronized (this) {
                    if (currentRetryTask == null) {
                        clearCurrentRetryTask();
                        exceptionHandler.accept(
                                new IllegalStateException(
                                        "Currently executing retry task was cleared." +
                                        " Likely a bug in the retry scheduler."
                                )
                        );
                        return;
                    }

                    final long taskRunTimeNanos = System.nanoTime();

                    // max to be robust against overflows.
                    if (Math.max(taskRunTimeNanos, taskRunTimeNanos + RESCHEDULING_OVERTAKING_TOLERANCE_NANOS) <
                        earliestNextRetryTimeNanos) {
                        // We are too early to execute the retry task.
                        logger.debug("Retry task is too early. Rescheduling...");

                        final Runnable currentRetryRunnable = currentRetryTask.getRetryTaskRunnable();
                        final Consumer<? super Throwable> currentExceptionHandler =
                                currentRetryTask.getExceptionHandler();

                        clearCurrentRetryTask();
                        // do not invoke rescheduleCurrentRetryTaskIfTooEarly here as it will not be able
                        // to cancel us.
                        scheduleNextRetryTask(currentRetryRunnable,
                                              earliestNextRetryTimeNanos,
                                              currentExceptionHandler, false);
                        return;
                    }
                }

                clearCurrentRetryTask();

                retryRunnable.run();
            };

            //noinspection unchecked
            final ScheduledFuture<Void> nextRetryTaskFuture =
                    (ScheduledFuture<Void>) eventLoop.schedule(wrappedRetryRunnable, delayNanos,
                                                               TimeUnit.NANOSECONDS);

            // We are passing in the original to avoid multiple wrapping in case of the retry task being
            // rescheduled multiple times.
            final RetryTaskHandle nextRetryTask = new RetryTaskHandle(nextRetryTaskFuture, retryRunnable,
                                                                      retryTimeNanos,
                                                                      exceptionHandler);

            nextRetryTaskFuture.addListener(f -> handleRetryTaskCompletion(nextRetryTask));
            currentRetryTask = nextRetryTask;
        } catch (Throwable t) {
            exceptionHandler.accept(t);
        }
    }

    public synchronized long addEarliestNextRetryTimeNanos(long earliestNextRetryTimeNanos) {
        checkState(earliestNextRetryTimeNanos <= latestNextRetryTimeNanos);
        this.earliestNextRetryTimeNanos = Math.max(this.earliestNextRetryTimeNanos,
                                                   earliestNextRetryTimeNanos);

        return this.earliestNextRetryTimeNanos;
    }

    public synchronized void rescheduleCurrentRetryTaskIfTooEarly() {
        if (currentRetryTask == null) {
            return;
        }

        if (
                Math.max(currentRetryTask.retryTimeNanos(),
                         currentRetryTask.retryTimeNanos() + RESCHEDULING_OVERTAKING_TOLERANCE_NANOS) <
                earliestNextRetryTimeNanos
        ) {
            // Current retry task is going to be executed before the earliestNextRetryTimeNanos so
            // we need to reschedule it.

            scheduleNextRetryTask(currentRetryTask.getRetryTaskRunnable(),
                                  earliestNextRetryTimeNanos,
                                  currentRetryTask.getExceptionHandler(), true);
        }
    }

    public boolean shutdown() {
        return cancelCurrentRetryTask(true);
    }
}
