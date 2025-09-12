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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.math.LongMath;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;

final class DefaultRetryScheduler implements RetryScheduler {
    private static final Logger logger = LoggerFactory.getLogger(DefaultRetryScheduler.class);

    private final EventLoop retryEventLoop;
    private final long deadlineTimeNanos;

    private final CompletableFuture<Void> closedFuture;
    private final RetryTaskWrapper retryTaskWrapper;

    @Nullable
    private Runnable nextRetryTask;
    @Nullable
    private ScheduledFuture<?> nextRetryTaskFuture;
    // Long.MIN_VALUE if not set.
    private long earliestRetryTimeNanos;
    private boolean isClosed;

    private final class RetryTaskWrapper implements Runnable {
        @Override
        public void run() {
            assert retryEventLoop.inEventLoop();

            if (isClosed) {
                logger.debug("Tried to run a retry task after the scheduler was closed. Skipping this task.");
                return;
            }

            if (System.nanoTime() > deadlineTimeNanos) {
                closeExceptionally(ResponseTimeoutException.get());
                return;
            }

            assert nextRetryTask != null;
            final Runnable retryTaskToRun = nextRetryTask;
            nextRetryTask = null;
            nextRetryTaskFuture = null;
            earliestRetryTimeNanos = Long.MIN_VALUE;

            try {
                retryTaskToRun.run();
            } catch (Throwable t) {
                // Normally we are running retry() which does not throw an exception
                // but let us be defensive here.
                closeExceptionally(t);
            }
        }
    }

    DefaultRetryScheduler(EventLoop retryEventLoop, long deadlineTimeNanos) {
        this.retryEventLoop = retryEventLoop;
        this.deadlineTimeNanos = deadlineTimeNanos;

        retryTaskWrapper = new RetryTaskWrapper();
        closedFuture = new CompletableFuture<>();

        nextRetryTask = null;
        nextRetryTaskFuture = null;
        earliestRetryTimeNanos = Long.MIN_VALUE;
        isClosed = false;
    }

    @Override
    public boolean trySchedule(Runnable retryTask, long delayMillis) {
        checkInRetryEventLoop();

        if (isClosed) {
            return false;
        }

        // We are a sequential scheduler there must not be a nextRetryTask already set.
        checkState(!hasNextRetryTask(), "cannot schedule a retry task when another is scheduled");

        assert nextRetryTask == null;
        assert nextRetryTaskFuture == null;

        final long retryTimeNanos = Math.max(
                LongMath.saturatedAdd(
                        System.nanoTime(),
                        TimeUnit.MILLISECONDS.toNanos(delayMillis)
                ),
                earliestRetryTimeNanos
        );

        if (retryTimeNanos >= deadlineTimeNanos) {
            return false;
        }

        try {
            final long nextRetryDelayMillis = TimeUnit.NANOSECONDS.toMillis(
                    retryTimeNanos - System.nanoTime());

            nextRetryTask = retryTask;
            if (nextRetryDelayMillis <= 0) {
                // Run immediately.
                nextRetryTaskFuture = null;
                retryTaskWrapper.run();
            } else {
                nextRetryTaskFuture = retryEventLoop.schedule(
                        retryTaskWrapper, nextRetryDelayMillis,
                        TimeUnit.MILLISECONDS);
                nextRetryTaskFuture.addListener(future -> {
                    if (isClosed) {
                        return;
                    }

                    if (future.isCancelled()) {
                        // future is cancelled when the client factory is closed.
                        closeExceptionally(new IllegalStateException(
                                ClientFactory.class.getSimpleName() + " has been closed."));
                    } else if (future.cause() != null) {
                        // Other unexpected exceptions.
                        closeExceptionally(future.cause());
                    }
                });
            }

            return true;
        } catch (Throwable t) {
            closeExceptionally(t);
            return false;
        }
    }

    @Override
    public void applyMinimumBackoffMillisForNextRetry(long minimumBackoffMillis) {
        checkInRetryEventLoop();

        if (isClosed) {
            return;
        }

        // We explicitly disallow that just to avoid having to implement cancelling and rescheduling
        // logic. A scheduler implementing hedging would need to do support that, however.
        checkState(!hasNextRetryTask(),
                   "cannot apply minimum backoff when a retry task is scheduled");

        earliestRetryTimeNanos =
                Math.min(
                        Math.max(earliestRetryTimeNanos,
                                 LongMath.saturatedAdd(System.nanoTime(),
                                                       TimeUnit.MILLISECONDS.toNanos(
                                                               minimumBackoffMillis))
                        ),
                        deadlineTimeNanos
                );
    }

    private boolean hasNextRetryTask() {
        checkInRetryEventLoop();
        // NOTE: nextRetryTask is null when scheduler is closed.
        return nextRetryTask != null;
    }

    @Override
    public void close() {
        checkInRetryEventLoop();

        if (isClosed) {
            return;
        }

        isClosed = true;
        clearRetryTaskIfExists();
        closedFuture.complete(null);
    }

    private void closeExceptionally(Throwable cause) {
        if (isClosed) {
            return;
        }

        isClosed = true;
        clearRetryTaskIfExists();
        closedFuture.completeExceptionally(cause);
    }

    @Override
    public CompletableFuture<Void> whenClosed() {
        checkInRetryEventLoop();

        return UnmodifiableFuture.wrap(closedFuture);
    }

    private void clearRetryTaskIfExists() {
        if (nextRetryTaskFuture != null) {
            nextRetryTaskFuture.cancel(false);
        }
        nextRetryTaskFuture = null;
        earliestRetryTimeNanos = Long.MIN_VALUE;
        nextRetryTask = null;
    }

    private void checkInRetryEventLoop() {
        checkState(retryEventLoop.inEventLoop(), "not in the retryEventLoop: %s but in thread %s",
                   retryEventLoop, Thread.currentThread().getName());
    }
}
