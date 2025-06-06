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

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

class RetryScheduler {
    private static class ScheduledRetryTask {
        private final Runnable retryTask;
        private final ScheduledFuture<Void> scheduledFuture;

        private final Consumer<? super Throwable> onRetryTaskFailedHandler;

        private final long retryTimeNanos;
        private boolean ignoreCancellation;

        ScheduledRetryTask(ScheduledFuture<Void> scheduledFuture, Runnable retryTask,
                           long retryTimeNanos,
                           Consumer<? super Throwable> onRetryTaskFailedHandler) {
            this.retryTask = retryTask;
            this.scheduledFuture = scheduledFuture;

            this.onRetryTaskFailedHandler = onRetryTaskFailedHandler;

            this.retryTimeNanos = retryTimeNanos;

            scheduledFuture.addListener(future -> {
                if (future.isCancelled()) {
                    if (!isIgnoringCancellation()) {
                        onRetryTaskFailedHandler.accept(new IllegalStateException(
                                ClientFactory.class.getSimpleName() + " has been closed."));
                    } else {
                        // We are cancelled because we are getting rescheduled.
                    }
                } else if (!future.isSuccess()) {
                    onRetryTaskFailedHandler.accept(future.cause());
                }
            });
        }

        private boolean isIgnoringCancellation() {
            return ignoreCancellation;
        }

        public Runnable getRetryTaskRunnable() {
            return retryTask;
        }

        public long retryTimeNanos() {
            return retryTimeNanos;
        }

        public Consumer<? super Throwable> getOnRetryTaskFailedHandler() {
            return onRetryTaskFailedHandler;
        }

        public boolean cancel() {
            ignoreCancellation = true;
            final boolean couldCancel = scheduledFuture.cancel(false);
            ignoreCancellation = false;
            return couldCancel;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(RetryScheduler.class);

    private final EventLoop eventLoop;
    private long earliestNextRetryTimeNanos;
    private final long latestNextRetryTimeNanos;
    private @Nullable ScheduledRetryTask currentRetryTask;

    RetryScheduler(EventLoop eventLoop) {
        this(eventLoop, Long.MAX_VALUE);
    }

    RetryScheduler(EventLoop eventLoop, long latestNextRetryTimeNanos) {
        this.eventLoop = requireNonNull(eventLoop, "eventLoop");
        currentRetryTask = null;
        earliestNextRetryTimeNanos = Long.MIN_VALUE;
        this.latestNextRetryTimeNanos = latestNextRetryTimeNanos;
    }

    public void schedule(Runnable retryTask, long nextRetryTimeNanos,
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
        }

        if (nextRetryTimeNanos > latestNextRetryTimeNanos) {
            // The next retry time is after the latestNextRetryTimeNanos.
            onRetryTaskFailedHandler.accept(
                    new IllegalStateException("nextRetryTimeNanos is after the latestNextRetryTimeNanos: " +
                                              nextRetryTimeNanos + " > " + latestNextRetryTimeNanos));
            return;
        }

        // "fast-path"
        if (currentRetryTask == null || nextRetryTimeNanos >= currentRetryTask.retryTimeNanos()) {
            // No retry task scheduled. We can schedule a new one directly.
            scheduleNextRetryTask(retryTask, nextRetryTimeNanos,
                                  onRetryTaskFailedHandler);
            return;
        }

        onRetryTaskFailedHandler.accept(
                new IllegalStateException("A retry task is already scheduled at " +
                                          currentRetryTask.retryTimeNanos() + ". " +
                                          "nextRetryTimeNanos: " + nextRetryTimeNanos));
    }

    private void scheduleNextRetryTask(Runnable retryRunnable, long retryTimeNanos,
                                       Consumer<? super Throwable> onRetryTaskFailedHandler) {
        assert earliestNextRetryTimeNanos <= retryTimeNanos;
        assert retryTimeNanos <= latestNextRetryTimeNanos;

        if (currentRetryTask != null) {
            if (!currentRetryTask.cancel()) {
                onRetryTaskFailedHandler.accept(
                        new IllegalStateException("Could not cancel the current retry task."));
                return;
            }
        }

        assert currentRetryTask == null;

        try {
            final long delayNanos = Math.max(retryTimeNanos - System.nanoTime(), 0);
            final Runnable wrappedRetryRunnable = () -> {
                logger.debug("Retry task starting. Resetting...");
                currentRetryTask = null;
                earliestNextRetryTimeNanos = Long.MIN_VALUE;
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
            currentRetryTask = new ScheduledRetryTask(nextRetryTaskFuture, retryRunnable,
                                                      retryTimeNanos,
                                                      onRetryTaskFailedHandler);
        } catch (Throwable t) {
            onRetryTaskFailedHandler.accept(t);
        }
    }

    public void close() {
        if (currentRetryTask != null) {
            currentRetryTask.cancel();
        }
    }

    // todo(szymon): Remove dependency on nextEarliestNextRetryTimeNanos. Users should simply set it before
    //   calling this method.
    public boolean hasAlreadyRetryScheduledBefore(long nextRetryTimeNanos,
                                                  long nextEarliestNextRetryTimeNanos) {
        checkState(nextEarliestNextRetryTimeNanos <= latestNextRetryTimeNanos);
        earliestNextRetryTimeNanos = Math.max(earliestNextRetryTimeNanos, nextEarliestNextRetryTimeNanos);

        if (currentRetryTask == null) {
            return false;
        }

        return Math.max(currentRetryTask.retryTimeNanos(), earliestNextRetryTimeNanos)
               <= nextRetryTimeNanos;
    }

    public void addEarliestNextRetryTimeNanos(long earliestNextRetryTimeNanos) {
        checkState(earliestNextRetryTimeNanos <= latestNextRetryTimeNanos);
        this.earliestNextRetryTimeNanos = Math.max(this.earliestNextRetryTimeNanos,
                                                   earliestNextRetryTimeNanos);
    }

    public long getEarliestNextRetryTimeNanos() {
        return earliestNextRetryTimeNanos;
    }

    public void rescheduleCurrentRetryTaskIfTooEarly() {
        if (currentRetryTask != null) {
            if (currentRetryTask.retryTimeNanos() < earliestNextRetryTimeNanos) {
                // Current retry task is going to be executed before the earliestNextRetryTimeNanos so
                // we need to reschedule it.
                scheduleNextRetryTask(currentRetryTask.getRetryTaskRunnable(),
                                      earliestNextRetryTimeNanos,
                                      currentRetryTask.getOnRetryTaskFailedHandler());
            }
        }
    }

    public boolean hasScheduledRetryTask() {
        return currentRetryTask != null;
    }
}
