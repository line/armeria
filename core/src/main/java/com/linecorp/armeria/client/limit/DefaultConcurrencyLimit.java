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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
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

        if (availablePermits() == 0 && pendingAcquisitions.size() >= maxPendingAcquisitions) {
            // Do not strictly calculate whether it exceeds the maxPendingAcquisitions using synchronized block.
            return CompletableFutures.exceptionallyCompletedFuture(ExceedingMaxPendingException.get());
        }

        final CompletableFuture<SafeCloseable> future = new CompletableFuture<>();
        final PendingAcquisition currentAcquire = new PendingAcquisition(future);
        pendingAcquisitions.add(currentAcquire);
        drain();

        if (!currentAcquire.isRun() && timeoutMillis != 0) {
            // Current acquire was not run. Schedule a timeout.
            final ScheduledFuture<?> timeoutFuture = ctx.eventLoop().withoutContext().schedule(
                    () -> future.completeExceptionally(
                            UnprocessedRequestException.of(ConcurrencyLimitTimeoutException.get())),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            currentAcquire.set(timeoutFuture);
        }

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
            }
        }
    }

    private final class PendingAcquisition extends AtomicReference<ScheduledFuture<?>> implements Runnable {

        private static final long serialVersionUID = -7092037489640350376L;

        private final CompletableFuture<SafeCloseable> future;
        private boolean isRun;

        PendingAcquisition(CompletableFuture<SafeCloseable> future) {
            this.future = future;
        }

        boolean isRun() {
            return isRun;
        }

        @Override
        public void run() {
            isRun = true;

            final ScheduledFuture<?> timeoutFuture = get();
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
