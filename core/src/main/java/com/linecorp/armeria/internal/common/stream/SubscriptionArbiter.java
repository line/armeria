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

package com.linecorp.armeria.internal.common.stream;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import io.netty.util.concurrent.EventExecutor;

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
public class SubscriptionArbiter implements Subscription {

    // Forked from https://github.com/oracle/helidon/blob/b64be21a5f5c7bbdecd6acf35339c6ee15da0af6/common/reactive/src/main/java/io/helidon/common/reactive/SubscriptionArbiter.java

    private final EventExecutor executor;
    /**
     * The new subscription to use.
     */
    @Nullable
    private Subscription newSubscription;

    /**
     * The current subscription to relay requests for.
     */
    @Nullable
    private Subscription subscription;

    /**
     * Requests accumulated.
     */
    private long newRequested;

    /**
     * Item production count accumulated.
     */
    private long newProduced;

    /**
     * The current outstanding request amount.
     */
    private long requested;

    /**
     * Used for serialized access.
     */
    private int wip;

    protected SubscriptionArbiter(EventExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void request(long n) {
        if (executor.inEventLoop()) {
            request0(n);
        } else {
            executor.execute(() -> request0(n));
        }
    }

    private void request0(long n) {
        if (newRequested < Long.MAX_VALUE) {
            newRequested = LongMath.saturatedAdd(newRequested, n);
        }
        drain();
    }

    @Override
    public void cancel() {
        if (executor.inEventLoop()) {
            cancel0();
        } else {
            executor.execute(this::cancel);
        }
    }

    private void cancel0() {
        final NoopSubscription noopSubscription = NoopSubscription.get();
        if (newSubscription == null) {
            newSubscription = noopSubscription;
        } else if (newSubscription != NoopSubscription.get()) {
            // Swap newSubscription with NoopSubscription and call cancel() on any previous Subscription held.
            final Subscription oldSubscription = newSubscription;
            newSubscription = noopSubscription;
            oldSubscription.cancel();
        }

        drain();
    }

    /**
     * Set the new subscription to resume with.
     * @param subscription the new subscription
     */
    public final void setUpstreamSubscription(Subscription subscription) {
        requireNonNull(subscription, "subscription");
        assert executor.inEventLoop();

        final Subscription previous = newSubscription;
        if (previous == NoopSubscription.get()) {
            // Cancelled already
            subscription.cancel();
            return;
        }
        newSubscription = subscription;

        drain();
    }

    /**
     * Indicate how many items were produced from the current subscription
     * before switching to the next subscription.
     * @param n the number of items produced, positive
     */
    protected final void produced(long n) {
        assert executor.inEventLoop();
        newProduced = LongMath.saturatedAdd(newProduced, n);
        drain();
    }

    private void drain() {
        if (wip++ > 0) {
            return;
        }

        long toRequest = 0L;
        Subscription requestFrom = null;

        do {
            // Get snapshots from volatile values and initialize them
            final long newReq = newRequested;
            if (newReq != 0L) {
                newRequested = 0;
            }
            final long newProd = newProduced;
            if (newProd != 0L) {
                newProduced = 0;
            }
            final Subscription next = newSubscription;
            final boolean isCancelled = next == NoopSubscription.get();
            if (next != null) {
                newSubscription = null;
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
        } while (--wip != 0);

        // request outside the serialization loop to avoid certain reentrance issues
        if (requestFrom != null && toRequest != 0L) {
            requestFrom.request(toRequest);
        }
    }
}
