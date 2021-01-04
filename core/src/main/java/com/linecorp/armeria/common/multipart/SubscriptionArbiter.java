/*
 * Copyright 2020 LINE Corporation
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
/*
 * Copyright (c)  2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.internal.common.stream.NoopSubscription;

/**
 * Allows changing the subscription, tracking requests and item
 * production in concurrent source-switching scenarios.
 * <p>
 *     {@code this} is the work-in-progress indicator for the
 *     subscriber-request-produced trampolining.
 * </p>
 * <p>
 *     Please override {@link #request(long)} and perform the n &lt;= 0L
 *     check in the context of the implementor because the TCK requires
 *     an onError signal and the arbiter has no contextual knowledge how
 *     and when to signal it.
 * </p>
 */
class SubscriptionArbiter extends AtomicInteger implements Subscription {

    // Forked from https://github.com/oracle/helidon/blob/b64be21a5f5c7bbdecd6acf35339c6ee15da0af6/common/reactive/src/main/java/io/helidon/common/reactive/SubscriptionArbiter.java

    private static final long serialVersionUID = 1163246596979976791L;

    /**
     * The new subscription to use.
     */
    private final AtomicReference<Subscription> newSubscription;

    /**
     * Requests accumulated.
     */
    private final AtomicLong newRequested;

    /**
     * Item production count accumulated.
     */
    private final AtomicLong newProduced;

    /**
     * The current subscription to relay requests for.
     */
    @Nullable
    private volatile Subscription subscription;

    /**
     * The current outstanding request amount.
     */
    private volatile long requested;

    SubscriptionArbiter() {
        newProduced = new AtomicLong();
        newRequested = new AtomicLong();
        newSubscription = new AtomicReference<>();
    }

    @Override
    public void request(long n) {
        addRequest(newRequested, n);
        drain();
    }

    /**
     * Atomically add the given request amount to the field while capping it at
     * {@link Long#MAX_VALUE}.
     * @param field the target field to update
     * @param n the request amount to add, must be positive (not verified)
     */
    private static void addRequest(AtomicLong field, long n) {
        for (;;) {
            final long current = field.get();
            if (current == Long.MAX_VALUE) {
                return;
            }
            final long update = LongMath.saturatedAdd(current, n);
            if (field.compareAndSet(current, update)) {
                return;
            }
        }
    }

    @Override
    public void cancel() {

        // Atomically swap in the NoopSubscription#get() instance and call cancel() on
        // any previous Subscription held.
        Subscription subscription = newSubscription.get();
        final NoopSubscription noopSubscription = NoopSubscription.get();
        if (subscription != noopSubscription) {
            subscription = newSubscription.getAndSet(noopSubscription);
            if (subscription != noopSubscription) {
                if (subscription != null) {
                    subscription.cancel();
                }
            }
        }

        drain();
    }

    /**
     * Set the new subscription to resume with.
     * @param subscription the new subscription
     */
    final void setSubscription(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        for (;;) {
            final Subscription previous = newSubscription.get();
            if (previous == NoopSubscription.get()) {
                // Cancelled already
                subscription.cancel();
                return;
            }
            if (newSubscription.compareAndSet(previous, subscription)) {
                break;
            }
        }

        drain();
    }

    /**
     * Indicate how many items were produced from the current subscription
     * before switching to the next subscription.
     * @param n the number of items produced, positive
     */
    final void produced(long n) {
        addRequest(newProduced, n);
        drain();
    }

    final void drain() {
        if (getAndIncrement() != 0) {
            return;
        }
        long toRequest = 0L;
        Subscription requestFrom = null;

        do {
            // Get snapshots from atomic values and initialize them
            long newReq = newRequested.get();
            if (newReq != 0L) {
                newReq = newRequested.getAndSet(0L);
            }
            long newProd = newProduced.get();
            if (newProd != 0L) {
                newProd = newProduced.getAndSet(0L);
            }
            final Subscription next = newSubscription.get();
            final boolean isCancelled = next == NoopSubscription.get();
            if (next != null) {
                newSubscription.compareAndSet(next, null);
            }

            if (isCancelled) {
                final Subscription s = subscription;
                subscription = null;
                if (s != null) {
                    s.cancel();
                }
                toRequest = 0L;
                requestFrom = null;
            } else {
                long currentRequested = requested;

                if (newReq != 0L) {
                    // Accumulate a newly requested number
                    currentRequested = LongMath.saturatedAdd(currentRequested, newReq);
                    toRequest = LongMath.saturatedAdd(toRequest, newReq);
                    requestFrom = subscription;
                }

                if (newProd != 0L && currentRequested != Long.MAX_VALUE) {
                    // Subtract the produced number from the requested number
                    currentRequested = Math.max(currentRequested - newProd, 0);
                }
                if (next != null) {
                    // A new subscription was set. Replace the old subscription with the new one.
                    subscription = next;
                    requestFrom = next;
                    toRequest = currentRequested;
                }
                requested = currentRequested;
            }
        } while (decrementAndGet() != 0);

        // request outside the serialization loop to avoid certain reentrance issues
        if (requestFrom != null && toRequest != 0L) {
            requestFrom.request(toRequest);
        }
    }
}
