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
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

/**
 * Builds a {@link ConcurrencyLimit}.
 */
@UnstableApi
public final class ConcurrencyLimitBuilder {

    static final CompletableFuture<SafeCloseable> noLimitFuture =
            UnmodifiableFuture.completedFuture(() -> { /* no-op */ });

    private static final ConcurrencyLimit noLimit = ctx -> noLimitFuture;

    static final long DEFAULT_TIMEOUT_MILLIS = 10000L;
    static final int DEFAULT_MAX_PENDING_ACQUIRES = Integer.MAX_VALUE;

    private final boolean useLimit;
    private final IntSupplier maxConcurrency;
    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    private int maxPendingAcquisitions = DEFAULT_MAX_PENDING_ACQUIRES;
    private Predicate<? super ClientRequestContext> predicate = requestContext -> true;

    ConcurrencyLimitBuilder(int maxConcurrency) {
        useLimit = !(maxConcurrency == 0 || maxConcurrency == Integer.MAX_VALUE);
        this.maxConcurrency = () -> maxConcurrency;
    }

    ConcurrencyLimitBuilder(IntSupplier maxConcurrency) {
        useLimit = true;
        this.maxConcurrency = maxConcurrency;
    }

    /**
     * Sets the amount of time until this decorator fails the request if the request was not
     * delegated to the {@code delegate} before then.
     */
    public ConcurrencyLimitBuilder timeoutMillis(long timeoutMillis) {
        checkArgument(timeoutMillis >= 0, "timeout: %s (expected: >= 0)", timeoutMillis);
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    /**
     * Sets the amount of time until this decorator fails the request if the request was not
     * delegated to the {@code delegate} before then.
     */
    public ConcurrencyLimitBuilder timeout(Duration timeout) {
        requireNonNull(timeout, "timeout");
        timeoutMillis(timeout.toMillis());
        return this;
    }

    /**
     * Sets the maximum number of pending acquisition. The {@link CompletableFuture} returned by
     * {@link ConcurrencyLimit#acquire(ClientRequestContext)} will be exceptionally complete with an
     * {@link TooManyPendingAcquisitionsException} if the pending exceeds this value.
     */
    public ConcurrencyLimitBuilder maxPendingAcquisitions(int maxPendingAcquisitions) {
        checkArgument(maxPendingAcquisitions >= 0,
                      "maxPendingAcquisitions: %s (expected: >= 0)", maxPendingAcquisitions);
        this.maxPendingAcquisitions = maxPendingAcquisitions;
        return this;
    }

    /**
     * Sets the {@link Predicate} for which to apply the concurrency limit.
     */
    public ConcurrencyLimitBuilder predicate(Predicate<? super ClientRequestContext> predicate) {
        this.predicate = requireNonNull(predicate, "predicate");
        return this;
    }

    /**
     * Returns a newly-created {@link ConcurrencyLimit} based on the properties of this builder.
     */
    public ConcurrencyLimit build() {
        if (!useLimit) {
            return noLimit;
        }
        return new DefaultConcurrencyLimit(predicate, maxConcurrency, maxPendingAcquisitions, timeoutMillis);
    }
}
