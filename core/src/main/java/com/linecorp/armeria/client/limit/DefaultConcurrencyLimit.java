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
import java.util.function.Predicate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * Concurrency settings that limits the concurrent number of active requests.
 */
final class DefaultConcurrencyLimit implements ConcurrencyLimit {

    private final Predicate<? super ClientRequestContext> predicate;
    private final int maxConcurrency;
    private final int maxPendingAcquisitions;
    private final long timeoutMillis;

    private final Queue<PendingAcquisition> pendingAcquisitions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger numPendingAcquisitions = new AtomicInteger();
    private final AtomicInteger acquiredPermits = new AtomicInteger();

    DefaultConcurrencyLimit(Predicate<? super ClientRequestContext> predicate,
                            int maxConcurrency, int maxPendingAcquisitions, long timeoutMillis) {
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
        return maxConcurrency - acquiredPermits.get();
    }

    @Override
    public CompletableFuture<SafeCloseable> acquire(ClientRequestContext ctx) {
        if (!predicate.test(ctx)) {
            return noLimitFuture;
        }

        if (pendingAcquisitions.isEmpty()) {
            if (acquiredPermits.incrementAndGet() <= maxConcurrency) {
                return CompletableFuture.completedFuture(new Permit());
            }
            acquiredPermits.decrementAndGet();
        }

        if (numPendingAcquisitions.incrementAndGet() > maxPendingAcquisitions) {
            numPendingAcquisitions.decrementAndGet();
            return CompletableFutures.exceptionallyCompletedFuture(
                    UnprocessedRequestException.of(TooManyPendingAcquisitionsException.get()));
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
            if (currentAcquiredPermits >= maxConcurrency) {
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
                numPendingAcquisitions.decrementAndGet();
            }
        }
    }

    private final class PendingAcquisition implements Runnable {

        private final CompletableFuture<SafeCloseable> future;
        @Nullable
        private final ScheduledFuture<?> timeoutFuture;

        PendingAcquisition(ClientRequestContext ctx, CompletableFuture<SafeCloseable> future) {
            this.future = future;
            if (timeoutMillis != 0) {
                timeoutFuture = ctx.eventLoop().withoutContext().schedule(
                        () -> future.completeExceptionally(
                                UnprocessedRequestException.of(ConcurrencyLimitTimeoutException.get())),
                        timeoutMillis, TimeUnit.MILLISECONDS);
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

            final Permit permit = new Permit();
            if (!future.complete(permit)) {
                permit.close();
            }
        }
    }

    private class Permit implements SafeCloseable {

        boolean closed;

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
