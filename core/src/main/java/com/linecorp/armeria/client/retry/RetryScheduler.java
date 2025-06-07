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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class RetryScheduler {
    private static class RetryTaskHandle {
        private final Runnable retryTask;
        private final ScheduledFuture<Void> scheduledFuture;
        private boolean isCancellationMuted;

        private final Consumer<@Nullable ? super Throwable> onExceptionHandler;

        private final long retryTimeNanos;

        RetryTaskHandle(ScheduledFuture<Void> scheduledFuture, Runnable retryTask,
                        long retryTimeNanos,
                        Consumer<@Nullable ? super Throwable> onExceptionHandler) {
            this.retryTask = retryTask;
            this.scheduledFuture = scheduledFuture;
            this.onExceptionHandler = onExceptionHandler;
            this.retryTimeNanos = retryTimeNanos;
        }

        public Runnable getRetryTaskRunnable() {
            return retryTask;
        }

        public long retryTimeNanos() {
            return retryTimeNanos;
        }

        public boolean isCancellationMuted() {
            return isCancellationMuted;
        }

        public void muteCancellation() {
            isCancellationMuted = true;
        }

        public void unmuteCancellation() {
            isCancellationMuted = false;
        }

        public Consumer<? super Throwable> getOnExceptionHandler() {
            return onExceptionHandler;
        }

        public ScheduledFuture<Void> getFuture() {
            return scheduledFuture;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(RetryScheduler.class);

    private final EventLoop eventLoop;
    private final long latestNextRetryTimeNanos;

    private long earliestNextRetryTimeNanos;
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
                                      Consumer<? super Throwable> onRetryTaskFailedHandler) {
        requireNonNull(retryTask, "retryTask");
        requireNonNull(onRetryTaskFailedHandler, "onRetryTaskFailedHandler");

        if (nextRetryTimeNanos < earliestNextRetryTimeNanos) {
            // The next retry time is before the earliestNextRetryTimeNanos.
            // We need to update the earliestNextRetryTimeNanos.
            onRetryTaskFailedHandler.accept(
                    new IllegalStateException(
                            "nextRetryTimeNanos is before the earliestNextRetryTimeNanos: " +
                            nextRetryTimeNanos + " < " + earliestNextRetryTimeNanos));
            return;
        }

        if (nextRetryTimeNanos > latestNextRetryTimeNanos) {
            // The next retry time is after the latestNextRetryTimeNanos.
            onRetryTaskFailedHandler.accept(
                    new IllegalStateException("nextRetryTimeNanos is after the latestNextRetryTimeNanos: " +
                                              nextRetryTimeNanos + " > " + latestNextRetryTimeNanos));
            return;
        }

        if (currentRetryTask == null || nextRetryTimeNanos < currentRetryTask.retryTimeNanos()) {
            scheduleNextRetryTask(retryTask, nextRetryTimeNanos, onRetryTaskFailedHandler);
            return;
        }

        onRetryTaskFailedHandler.accept(
                new IllegalStateException("A retry task is already scheduled at " +
                                          currentRetryTask.retryTimeNanos() + ". " +
                                          "nextRetryTimeNanos: " + nextRetryTimeNanos));
    }

    private synchronized boolean cancelCurrentRetryTask() {
        if (currentRetryTask != null) {
            final ScheduledFuture<Void> retryTaskFuture = currentRetryTask.getFuture();

            currentRetryTask.muteCancellation();
            if (!retryTaskFuture.cancel(false)) {
                currentRetryTask.unmuteCancellation();
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
            if (!retryTaskHandle.isCancellationMuted()) {
                // The retry task was cancelled by the user, not by the scheduler.
                retryTaskHandle.getOnExceptionHandler().accept(
                        new IllegalStateException("Retry task was cancelled by the user."));
            }
            return;
        }

        if (!retryTaskFuture.isSuccess()) {
            retryTaskHandle.getOnExceptionHandler().accept(retryTaskFuture.cause());
        }
    }

    private synchronized void clearCurrentRetryTask() {
        currentRetryTask = null;
        earliestNextRetryTimeNanos = Long.MIN_VALUE;
    }

    private synchronized void scheduleNextRetryTask(Runnable retryRunnable, long retryTimeNanos,
                                                    Consumer<? super Throwable> onExceptionHandler) {
        assert earliestNextRetryTimeNanos <= retryTimeNanos;
        assert retryTimeNanos <= latestNextRetryTimeNanos;

        if (!cancelCurrentRetryTask()) {
            onExceptionHandler.accept(
                    new IllegalStateException("Could not cancel the current retry task."));
            return;
        }

        assert currentRetryTask == null;

        try {
            final long delayNanos = Math.max(retryTimeNanos - System.nanoTime(), 0);
            final Runnable wrappedRetryRunnable = () -> {
                logger.debug("Retry task starting. Resetting...");
                // todo(szymon): do sanity check that we are clearing this task (very bad otherwise).
                clearCurrentRetryTask();
                retryRunnable.run();
            };

            logger.debug("Scheduling the retry task. delayNanos = {}, "
                         + "retryTimeNanos = {}, earliestNextRetryTimeNanos = {}",
                         delayNanos, retryTimeNanos, earliestNextRetryTimeNanos);

            //noinspection unchecked
            final ScheduledFuture<Void> nextRetryTaskFuture =
                    (ScheduledFuture<Void>) eventLoop.schedule(wrappedRetryRunnable, delayNanos,
                                                               TimeUnit.NANOSECONDS);

            // We are passing in the original to avoid multiple wrapping in case of the retry task being
            // rescheduled multiple times.
            final RetryTaskHandle nextRetryTask = new RetryTaskHandle(nextRetryTaskFuture, retryRunnable,
                                                                      retryTimeNanos,
                                                                      onExceptionHandler);

            nextRetryTaskFuture.addListener(f -> handleRetryTaskCompletion(nextRetryTask));
            currentRetryTask = nextRetryTask;
        } catch (Throwable t) {
            onExceptionHandler.accept(t);
        }
    }

    public void close() {
        cancelCurrentRetryTask();
    }

    // todo(szymon): Remove dependency on nextEarliestNextRetryTimeNanos. Users should simply set it before
    //   calling this method.
    public synchronized boolean hasAlreadyRetryScheduledBefore(long nextRetryTimeNanos,
                                                               long nextEarliestNextRetryTimeNanos) {
        checkState(nextEarliestNextRetryTimeNanos <= latestNextRetryTimeNanos);
        earliestNextRetryTimeNanos = Math.max(earliestNextRetryTimeNanos, nextEarliestNextRetryTimeNanos);

        if (currentRetryTask == null) {
            return false;
        }

        return Math.max(currentRetryTask.retryTimeNanos(), earliestNextRetryTimeNanos)
               <= nextRetryTimeNanos;
    }

    public synchronized void addEarliestNextRetryTimeNanos(long earliestNextRetryTimeNanos) {
        checkState(earliestNextRetryTimeNanos <= latestNextRetryTimeNanos);
        this.earliestNextRetryTimeNanos = Math.max(this.earliestNextRetryTimeNanos,
                                                   earliestNextRetryTimeNanos);
    }

    public synchronized long getEarliestNextRetryTimeNanos() {
        return earliestNextRetryTimeNanos;
    }

    public synchronized void rescheduleCurrentRetryTaskIfTooEarly() {
        if (currentRetryTask != null) {
            if (currentRetryTask.retryTimeNanos() < earliestNextRetryTimeNanos) {
                // Current retry task is going to be executed before the earliestNextRetryTimeNanos so
                // we need to reschedule it.
                scheduleNextRetryTask(currentRetryTask.getRetryTaskRunnable(),
                                      earliestNextRetryTimeNanos,
                                      currentRetryTask.getOnExceptionHandler());
            }
        }
    }

    public synchronized boolean hasScheduledRetryTask() {
        return currentRetryTask != null;
    }
}
