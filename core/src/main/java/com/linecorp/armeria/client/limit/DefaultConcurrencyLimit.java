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

import static com.linecorp.armeria.client.limit.ConcurrencyLimitBuilder.noLimitFuture;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * Concurrency settings that limits the concurrent number of active requests.
 */
final class DefaultConcurrencyLimit implements ConcurrencyLimit {

    private final Predicate<? super ClientRequestContext> predicate;
    private final SettableLimit maxConcurrency;
    private final int maxPendingAcquisitions;
    private final long timeoutMillis;

    private final Queue<PendingAcquisition> pendingAcquisitions = new ConcurrentLinkedQueue<>();
    private final AtomicLong numPendingAcquisitions = new AtomicLong();
    private final AtomicInteger acquiredPermits = new AtomicInteger();

    /**
     *
     * @deprecated Use {@link #DefaultConcurrencyLimit(Predicate, SettableLimit, int, long)} instead.
     */
    @Deprecated
    DefaultConcurrencyLimit(Predicate<? super ClientRequestContext> predicate,
                            int maxConcurrency, int maxPendingAcquisitions, long timeoutMillis) {
        this(predicate, new SettableLimit(maxConcurrency), maxPendingAcquisitions, timeoutMillis);
    }

    DefaultConcurrencyLimit(Predicate<? super ClientRequestContext> predicate,
                            SettableLimit maxConcurrency, int maxPendingAcquisitions, long timeoutMillis) {
        this.predicate = predicate;
        this.maxConcurrency = maxConcurrency;
        this.maxPendingAcquisitions = maxPendingAcquisitions;
        this.timeoutMillis = timeoutMillis;
    }

    @VisibleForTesting
    int acquiredPermits() {
        return acquiredPermits.get();
    }

    @VisibleForTesting
    int availablePermits() {
        int availablePermitCount = maxConcurrency() - acquiredPermits.get();
        return Math.max(availablePermitCount, 0);
    }

    @VisibleForTesting
    int maxConcurrency() {
        int maxConcurrency = this.maxConcurrency.get();
        if (maxConcurrency <= 0) {
            return Integer.MAX_VALUE;
        }
        return maxConcurrency;
    }

    @Override
    public CompletableFuture<SafeCloseable> acquire(ClientRequestContext ctx) {
        if (!predicate.test(ctx)) {
            return noLimitFuture;
        }

        if (pendingAcquisitions.isEmpty()) {
            // Because we don't do checking acquiredPermits and adding the queue at the same time,
            // this doesn't strictly guarantee FIFO.
            // However, the reversal happens within a reasonable window so it should be fine.
            if (acquiredPermits.incrementAndGet() <= maxConcurrency()) {
                return CompletableFuture.completedFuture(new Permit());
            }
            acquiredPermits.decrementAndGet();
        }

        if (maxPendingAcquisitions == 0) {
            return CompletableFutures.exceptionallyCompletedFuture(TooManyPendingAcquisitionsException.get());
        }
        if (numPendingAcquisitions.incrementAndGet() > maxPendingAcquisitions) {
            numPendingAcquisitions.decrementAndGet();
            return CompletableFutures.exceptionallyCompletedFuture(TooManyPendingAcquisitionsException.get());
        }

        final CompletableFuture<SafeCloseable> future = new CompletableFuture<>();
        final PendingAcquisition pendingAcquisition = new PendingAcquisition(ctx, future);
        pendingAcquisitions.add(pendingAcquisition);
        // Call drain to check if there's available permits in the meantime.
        drain();
        return future;
    }

    void drain() {
        while (!pendingAcquisitions.isEmpty()) {
            final int currentAcquiredPermits = acquiredPermits.get();
            if (currentAcquiredPermits >= maxConcurrency()) {
                break;
            }

            if (acquiredPermits.compareAndSet(currentAcquiredPermits, currentAcquiredPermits + 1)) {
                final PendingAcquisition task = pendingAcquisitions.poll();
                if (task == null) {
                    acquiredPermits.decrementAndGet();
                    if (!pendingAcquisitions.isEmpty()) {
                        // Another task might have been added to the queue while acquiredPermits reached
                        // at its limit.
                        continue;
                    } else {
                        break;
                    }
                }

                task.run();
            }
        }
    }

    private final class PendingAcquisition implements Runnable {

        private final ClientRequestContext ctx;
        private final CompletableFuture<SafeCloseable> future;
        @Nullable
        private final ScheduledFuture<?> timeoutFuture;

        PendingAcquisition(ClientRequestContext ctx, CompletableFuture<SafeCloseable> future) {
            this.ctx = ctx;
            this.future = future;
            if (timeoutMillis != 0) {
                timeoutFuture = ctx.eventLoop().withoutContext().schedule(
                        () -> {
                            future.completeExceptionally(ConcurrencyLimitTimeoutException.get());
                            numPendingAcquisitions.decrementAndGet();
                        }, timeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                timeoutFuture = null;
            }
        }

        @Override
        public void run() {
            if (timeoutFuture != null) {
                if (timeoutFuture.isDone() || !timeoutFuture.cancel(false)) {
                    // Timeout task ran already or is determined to run.
                    acquiredPermits.decrementAndGet();
                    return;
                }
            }
            // numPendingAcquisitions is decreased in two places:
            // - In the Runnable of a timeout scheduledFuture.
            // - Here, which is when the PendingAcquisition is executed.
            numPendingAcquisitions.decrementAndGet();

            final ContextAwareEventLoop eventLoop = ctx.eventLoop();
            if (eventLoop.inEventLoop()) {
                try (SafeCloseable ignored = ctx.replace()) {
                    // Call ctx.replace() because the current EventLoop might
                    // be a different ContextAwareEventLoop.
                    completePermit();
                }
            } else {
                eventLoop.execute(this::completePermit);
            }
        }

        private void completePermit() {
            final Permit permit = new Permit();
            if (!future.complete(permit)) {
                permit.close();
            }
        }
    }

    private class Permit implements SafeCloseable {

        private boolean closed;

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            acquiredPermits.decrementAndGet();
            drain();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("maxConcurrency", maxConcurrency)
                          .add("maxPendingAcquisitions", maxPendingAcquisitions)
                          .add("acquiredPermits", acquiredPermits)
                          .add("timeoutMillis", timeoutMillis)
                          .toString();
    }
}
