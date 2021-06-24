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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Integer.MAX_VALUE;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.netty.util.concurrent.ScheduledFuture;

/**
 * Concurrency settings that limits the concurrent number of active requests.
 */
public final class DefaultConcurrencyLimit implements ConcurrencyLimit<ClientRequestContext> {
    /**
     * Returns a new {@link ConcurrencyLimitBuilder} with the specified {@code maxConcurrency}.
     *
     * @param maxConcurrency the maximum number of concurrent active requests,
     *                       specify {@code 0} to disable the limit.
     */
    public static ConcurrencyLimitBuilder builder(int maxConcurrency) {
        checkArgument(maxConcurrency >= 0,
                      "maxConcurrency: %s (expected: >= 0)", maxConcurrency);
        return new ConcurrencyLimitBuilder(maxConcurrency == MAX_VALUE ? 0 : maxConcurrency);
    }

    /**
     * Returns a new {@link ConcurrencyLimitBuilder} with the specified {@code maxConcurrency}.
     *
     * @param maxConcurrency the maximum number of concurrent active requests,
     *                       specify {@code 0} to disable the limit.
     */
    public static ConcurrencyLimitBuilder of(int maxConcurrency) {
        return builder(maxConcurrency);
    }

    private final Predicate<? super ClientRequestContext> predicate;
    private final int maxConcurrency;
    private final long timeoutMillis;
    private final AtomicInteger numActiveRequests = new AtomicInteger();
    private final Queue<PendingTask> pendingRequests = new ConcurrentLinkedQueue<>();
    private final Permit semaphorePermit = () -> drain();


    DefaultConcurrencyLimit(Predicate<? super ClientRequestContext> predicate, int maxConcurrency,
                            long timeoutMillis) {
        this.predicate = predicate;
        this.maxConcurrency = maxConcurrency;
        this.timeoutMillis = timeoutMillis;
    }

    private CompletableFuture<Permit> newFuturePermit() {
        return CompletableFuture.completedFuture(semaphorePermit);
    }

    /**
     * Returns the value of the {@code "maxConcurrency"}.
     */
    public int maxConcurrency() {
        return maxConcurrency;
    }

    /**
     * Returns the value of the {@code "timeout"} in milliseconds.
     */
    public long timeoutMillis() {
        return timeoutMillis;
    }

    @Override
    public CompletableFuture<Permit> acquire(ClientRequestContext requestContext) {
        requireNonNull(requestContext, "requestContext");
        return predicate.test(requestContext)
               ? limitedPermit(requestContext)
               : newFuturePermit();
    }

    public int acquiredPermitCount() {
        return numActiveRequests.get();
    }

    private CompletableFuture<Permit> limitedPermit(ClientRequestContext ctx) {
        numActiveRequests.incrementAndGet();

        final CompletableFuture<Permit> resFuture = newFuturePermit();
        final PendingTask currentTask = new PendingTask(resFuture);

        pendingRequests.add(currentTask);
        drain();

        if (!currentTask.isRun() && timeoutMillis != 0) {
            // Current request was not delegated. Schedule a timeout.
            final ScheduledFuture<?> timeoutFuture = ctx.eventLoop().withoutContext().schedule(
                    () -> resFuture.completeExceptionally(
                            UnprocessedRequestException.of(RequestTimeoutException.get())),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            currentTask.set(timeoutFuture);
        }

        return resFuture;
    }

    AtomicInteger numActiveRequests() {
        return numActiveRequests;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultConcurrencyLimit)) {
            return false;
        }
        final DefaultConcurrencyLimit that = (DefaultConcurrencyLimit) o;
        return maxConcurrency == that.maxConcurrency && timeoutMillis == that.timeoutMillis &&
               predicate.equals(that.predicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(predicate, maxConcurrency, timeoutMillis);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("predicate", predicate)
                          .add("maxConcurrency", maxConcurrency)
                          .add("timeoutMillis", timeoutMillis)
                          .toString();
    }

    final void drain() {
        while (!pendingRequests.isEmpty()) {
            final int currentActiveRequests = numActiveRequests.get();
            if (currentActiveRequests >= maxConcurrency) {
                break;
            }

            if (numActiveRequests.compareAndSet(currentActiveRequests, currentActiveRequests + 1)) {
                final PendingTask task = pendingRequests.poll();
                if (task == null) {
                    numActiveRequests.decrementAndGet();
                    if (!pendingRequests.isEmpty()) {
                        // Another request might have been added to the queue while numActiveRequests reached
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

    private final class PendingTask extends AtomicReference<ScheduledFuture<?>> implements Runnable {

        private static final long serialVersionUID = -7092037489640350376L;
        private final CompletableFuture<Permit> resFuture;
        private boolean isRun;

        public PendingTask(CompletableFuture<Permit> resFuture) {
            this.resFuture = resFuture;
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
                    numActiveRequests.decrementAndGet();
                    return;
                }
            }

            resFuture.whenComplete((value, cause) -> {
                drain();
            });

        }
    }
}
