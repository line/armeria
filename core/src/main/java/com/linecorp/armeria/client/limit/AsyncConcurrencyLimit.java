/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.limit;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.channel.EventLoop;

/**
 * Concurrency settings that limits the concurrent number of active requests.
 */
final class AsyncConcurrencyLimit implements ConcurrencyLimit {
    private static final UnprocessedRequestException REQUEST_EXCEPTION = UnprocessedRequestException.of(
            ConcurrencyLimitTimeoutException.get());
    private static final IllegalStateException TOO_MANY_OUTSTANDING_ACQUIRE_OPERATIONS =
            new IllegalStateException("Too many outstanding acquire operations");
    private final int maxParallelism;
    private final int maxPendingAcquires;
    private final long acquireTimeoutNanos;

    private final Queue<AcquireTask> pendingAcquireQueue;
    private final AtomicInteger acquiredPermitCount = new AtomicInteger();
    private final Runnable timeoutTask;
    private int pendingAcquireCount;
    private final SafeCloseable semaphorePermit = this::decrementAndRunTaskQueue;

    AsyncConcurrencyLimit(
            long acquireTimeoutMillis,
            int maxParallelism,
            int maxPendingAcquires) {
        this.maxParallelism = maxParallelism;
        this.maxPendingAcquires = maxPendingAcquires;
        pendingAcquireQueue = new ArrayDeque<>(maxPendingAcquires);
        acquireTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMillis);
        timeoutTask = new TimeoutTask();
    }

    /**
     * Returns the total number of acquired permits.
     */
    @Override
    public int acquiredPermits() {
        return acquiredPermitCount.get();
    }

    /**
     * Returns the total number of available permits.
     */
    @Override
    public int availablePermits() {
        return maxParallelism - acquiredPermits();
    }

    @Override
    public CompletableFuture<SafeCloseable> acquire(ClientRequestContext requestContext) {
        final CompletableFuture<SafeCloseable> future = new CompletableFuture<>();
        final EventLoop executor = requestContext.eventLoop().withoutContext();
        try {
            if (executor.inEventLoop()) {
                acquire0(executor, future);
            } else {
                executor.execute(() -> acquire0(executor, future));
            }
        } catch (Throwable cause) {
            future.completeExceptionally(cause);
        }
        return future;
    }

    private void acquire0(EventLoop executor, final CompletableFuture<SafeCloseable> future) {
        assert executor.inEventLoop();

        if (acquiredPermitCount.get() < maxParallelism) {
            assert acquiredPermitCount.get() >= 0;
            // Acquire immediately.
            acquiredPermitCount.incrementAndGet();
            future.complete(semaphorePermit);
        } else {
            if (pendingAcquireCount >= maxPendingAcquires) {
                tooManyOutstanding(future);
            } else {
                final ScheduledFuture<?> timeoutFuture = executor.schedule(timeoutTask,
                                                                           acquireTimeoutNanos,
                                                                           TimeUnit.NANOSECONDS);
                final AcquireTask task = new AcquireTask(executor, future, timeoutFuture);
                pendingAcquireQueue.offer(task);
                ++pendingAcquireCount;
            }

            assert pendingAcquireCount > 0;
        }
    }

    private void tooManyOutstanding(CompletableFuture<?> future) {
        future.completeExceptionally(TOO_MANY_OUTSTANDING_ACQUIRE_OPERATIONS);
    }

    private void decrementAndRunTaskQueue() {
        // We should never have a negative value.
        final int currentCount = acquiredPermitCount.decrementAndGet();
        assert currentCount >= 0;

        // Run the pending acquire tasks before notify the original promise so if the user would
        // try to acquire again from the ChannelFutureListener and the pendingAcquireCount is >=
        // maxPendingAcquires we may be able to run some pending tasks first and so allow to add
        // more.
        runTaskQueue();
    }

    private void runTaskQueue() {
        while (acquiredPermitCount.get() < maxParallelism) {
            final AcquireTask task = pendingAcquireQueue.poll();
            if (task == null) {
                break;
            }

            // Cancel the timeout if one was scheduled
            final ScheduledFuture<?> timeoutFuture = task.timeoutFuture;
            timeoutFuture.cancel(false);

            --pendingAcquireCount;
            task.acquired();

            task.future.complete(semaphorePermit);
        }

        // We should never have a negative value.
        assert pendingAcquireCount >= 0;
        assert acquiredPermitCount.get() >= 0;
    }

    private final class AcquireTask {
        final CompletableFuture<SafeCloseable> future;
        final long expireNanoTime = System.nanoTime() + acquireTimeoutNanos;
        final EventLoop executor;
        final ScheduledFuture<?> timeoutFuture;
        boolean acquired;

        AcquireTask(EventLoop executor, CompletableFuture<SafeCloseable> future,
                    ScheduledFuture<?> timeoutFuture) {
            this.executor = executor;
            this.timeoutFuture = timeoutFuture;
            this.future = future;
        }

        public void acquired() {
            if (acquired) {
                return;
            }
            acquiredPermitCount.incrementAndGet();
            acquired = true;
        }
    }

    private class TimeoutTask implements Runnable {
        @Override
        public final void run() {
            final long nanoTime = System.nanoTime();
            for (;;) {
                final AcquireTask task = pendingAcquireQueue.peek();
                if (task == null || nanoTime - task.expireNanoTime < 0) {
                    break;
                }
                pendingAcquireQueue.remove();
                --pendingAcquireCount;

                task.acquired();
                task.future.completeExceptionally(REQUEST_EXCEPTION);
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("maxParallelism", maxParallelism)
                          .add("maxPendingAcquires", maxPendingAcquires)
                          .add("acquireTimeoutNanos", acquireTimeoutNanos)
                          .add("acquiredPermitCount", acquiredPermitCount)
                          .add("pendingAcquireCount", pendingAcquireCount)
                          .toString();
    }
}
