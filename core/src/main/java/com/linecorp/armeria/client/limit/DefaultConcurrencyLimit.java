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
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.ContextAwareEventLoop;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.RequestCancellation;

/**
 * Concurrency settings that limits the concurrent number of active requests.
 */
final class DefaultConcurrencyLimit implements ConcurrencyLimit {
    private static final Logger logger = LoggerFactory.getLogger(DefaultConcurrencyLimit.class);

    private final Predicate<? super ClientRequestContext> predicate;
    private final IntSupplier maxConcurrency;
    private final int maxPendingAcquisitions;
    private final long timeoutMillis;

    private final Queue<Runnable> pendingAcquisitions = new ConcurrentLinkedQueue<>();
    private final AtomicLong numPendingAcquisitions = new AtomicLong();
    private final AtomicInteger acquiredPermits = new AtomicInteger();

    DefaultConcurrencyLimit(Predicate<? super ClientRequestContext> predicate,
                            IntSupplier maxConcurrency, int maxPendingAcquisitions, long timeoutMillis) {
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
        final int availablePermitCount = maxConcurrency() - acquiredPermits.get();
        return Math.max(availablePermitCount, 0);
    }

    @VisibleForTesting
    int maxConcurrency() {
        final int maxConcurrency = this.maxConcurrency.getAsInt();
        if (maxConcurrency < 0) {
            logger.warn("maxConcurrency.get() returned {}; maxConcurrency is set to 0.", maxConcurrency);
            return 0;
        }
        return maxConcurrency;
    }

    @Override
    public CompletableFuture<SafeCloseable> acquire(ClientRequestContext ctx) {
        if (!predicate.test(ctx)) {
            return noLimitFuture;
        }

        final RequestCancellation cancellation = RequestCancellation.get(ctx);
        if (cancellation != null) {
            final Throwable cause = cancellation.cause();
            if (cause != null) {
                return UnmodifiableFuture.exceptionallyCompletedFuture(cause);
            }
        }

        if (pendingAcquisitions.isEmpty()) {
            // Because we don't do checking acquiredPermits and adding the queue at the same time,
            // this doesn't strictly guarantee FIFO.
            // However, the reversal happens within a reasonable window so it should be fine.
            if (acquiredPermits.incrementAndGet() <= maxConcurrency()) {
                final Permit permit = new Permit();
                if (cancellation != null) {
                    final Throwable cause = cancellation.cause();
                    if (cause != null) {
                        permit.close();
                        return UnmodifiableFuture.exceptionallyCompletedFuture(cause);
                    }
                }
                return UnmodifiableFuture.completedFuture(permit);
            }
            acquiredPermits.decrementAndGet();
        }

        if (maxPendingAcquisitions == 0) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(TooManyPendingAcquisitionsException.get());
        }
        if (numPendingAcquisitions.incrementAndGet() > maxPendingAcquisitions) {
            numPendingAcquisitions.decrementAndGet();
            return UnmodifiableFuture.exceptionallyCompletedFuture(TooManyPendingAcquisitionsException.get());
        }

        final CompletableFuture<SafeCloseable> future = new CompletableFuture<>();
        if (cancellation == null) {
            pendingAcquisitions.add(new PendingAcquisition(ctx, future));
        } else {
            final CancellablePendingAcquisition pendingAcquisition =
                    new CancellablePendingAcquisition(ctx, future);
            pendingAcquisitions.add(pendingAcquisition);
            pendingAcquisition.initialize(cancellation);
        }
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
                final Runnable task = pendingAcquisitions.poll();
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

    private enum AcquisitionState {
        QUEUED,
        GRANTING,
        DONE
    }

    private final class CancellablePendingAcquisition implements Runnable {

        private final ClientRequestContext ctx;
        private final CompletableFuture<SafeCloseable> future;
        // QUEUED owns one pending-count entry. Grant, timeout, and cancellation compete
        // under this monitor; only the transition out of QUEUED decrements that count.
        // GRANTING owns a reserved permit until event-loop handoff. DONE claims the
        // terminal outcome before future completion.
        private AcquisitionState state = AcquisitionState.QUEUED;
        @Nullable
        private Permit permit;
        @Nullable
        private volatile ScheduledFuture<?> timeoutFuture;
        @Nullable
        private volatile SafeCloseable cancellationRegistration;

        CancellablePendingAcquisition(ClientRequestContext ctx, CompletableFuture<SafeCloseable> future) {
            this.ctx = ctx;
            this.future = future;
        }

        void initialize(RequestCancellation cancellation) {
            final SafeCloseable registration = cancellation.onCancel(this::cancel);
            cancellationRegistration = registration;
            final boolean done;
            final boolean queued;
            // Immediate cancellation can finish before the registration field is assigned.
            synchronized (this) {
                done = state == AcquisitionState.DONE;
                queued = state == AcquisitionState.QUEUED;
            }
            if (done) {
                registration.close();
            }
            if (timeoutMillis == 0 || !queued) {
                return;
            }
            try {
                final ScheduledFuture<?> timeoutFuture = ctx.eventLoop().withoutContext().schedule(
                        this::timeout, timeoutMillis, TimeUnit.MILLISECONDS);
                this.timeoutFuture = timeoutFuture;
                final boolean cancelTimeout;
                synchronized (this) {
                    cancelTimeout = state != AcquisitionState.QUEUED;
                }
                if (cancelTimeout) {
                    timeoutFuture.cancel(false);
                }
            } catch (Throwable cause) {
                cancel(cause);
            }
        }

        @Override
        public void run() {
            synchronized (this) {
                if (state != AcquisitionState.QUEUED) {
                    // drain() reserved a permit before dequeuing this canceled or timed-out entry.
                    // Its pending count is already removed, so return only the permit reservation.
                    acquiredPermits.decrementAndGet();
                    return;
                }
                state = AcquisitionState.GRANTING;
                numPendingAcquisitions.decrementAndGet();
                permit = new Permit();
            }
            cancelTimeout();
            try {
                final ContextAwareEventLoop eventLoop = ctx.eventLoop();
                if (eventLoop.inEventLoop()) {
                    try (SafeCloseable ignored = ctx.replace()) {
                        completePermit();
                    }
                } else {
                    eventLoop.execute(this::completePermit);
                }
            } catch (Throwable cause) {
                cancel(cause);
            }
        }

        private void completePermit() {
            final Permit permit;
            synchronized (this) {
                if (state != AcquisitionState.GRANTING) {
                    return;
                }
                // Claim DONE before completing the future so cancellation cannot release this permit.
                // The local variable owns it until completion transfers it to the caller,
                // or a failed completion releases it.
                state = AcquisitionState.DONE;
                permit = this.permit;
                this.permit = null;
            }
            assert permit != null;
            closeRegistration();
            if (!future.complete(permit)) {
                permit.close();
            }
        }

        private void timeout() {
            synchronized (this) {
                if (state != AcquisitionState.QUEUED) {
                    return;
                }
                state = AcquisitionState.DONE;
                numPendingAcquisitions.decrementAndGet();
            }
            pendingAcquisitions.remove(this);
            closeRegistration();
            future.completeExceptionally(ConcurrencyLimitTimeoutException.get());
        }

        private void cancel(Throwable cause) {
            final Permit permit;
            synchronized (this) {
                if (state == AcquisitionState.DONE) {
                    return;
                }
                if (state == AcquisitionState.QUEUED) {
                    numPendingAcquisitions.decrementAndGet();
                }
                state = AcquisitionState.DONE;
                permit = this.permit;
                this.permit = null;
            }
            pendingAcquisitions.remove(this);
            cancelTimeout();
            closeRegistration();
            if (permit != null) {
                permit.close();
            }
            future.completeExceptionally(cause);
        }

        private void cancelTimeout() {
            final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        }

        private void closeRegistration() {
            final SafeCloseable cancellationRegistration = this.cancellationRegistration;
            if (cancellationRegistration != null) {
                cancellationRegistration.close();
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
