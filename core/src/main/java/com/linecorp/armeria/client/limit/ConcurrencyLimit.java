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
import static com.linecorp.armeria.client.limit.AbstractConcurrencyLimitingClient.validateMaxConcurrency;
import static java.lang.Integer.MAX_VALUE;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestContext;

/**
 * Concurrency settings that limits the concurrent number of active requests.
 */
public final class ConcurrencyLimit {
    private static final long DEFAULT_TIMEOUT_MILLIS = 10000L;

    /**
     * Returns a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    private final Predicate<RequestContext> policy;
    private final int maxConcurrency;
    private final long timeoutMillis;
    private final AtomicInteger numActiveRequests = new AtomicInteger();

    /**
     * Creates a new instance to limit the concurrent number of active requests to {@code maxConcurrency}.
     *
     * @param policy the policy to apply on the incoming {@code RequestContext} if the request should be
     *      limited or not.
     * @param maxConcurrency the maximum number of concurrent active requests. {@code 0} to disable the limit.
     * @param timeoutMillis the amount of time until this decorator fails the request if the request was not
     *                delegated to the {@code delegate} before then.
     */
    ConcurrencyLimit(Predicate<RequestContext> policy, int maxConcurrency, long timeoutMillis) {
        this.policy = policy;
        this.maxConcurrency = maxConcurrency;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Returns the value of the {@code "maxConcurrency"}.
     */
    public int maxConcurrency() {
        return maxConcurrency;
    }

    /**
     * Returns the value of the {@code "timeout"} with the desired {@code "unit"}.
     */
    public long timeout(TimeUnit desiredUnit) {
        requireNonNull(desiredUnit, "desiredUnit");
        return desiredUnit.convert(timeoutMillis, MILLISECONDS);
    }

    /**
     * Returns the value of the {@code "timeout"} in milliseconds.
     */
    public long timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Checks if the concurrency control should be enforced for the given {@code requestContext}.
     */
    public Boolean shouldLimit(ClientRequestContext requestContext) {
        return maxConcurrency > 0 && policy.test(requestContext);
    }

    AtomicInteger numActiveRequests() {
        return numActiveRequests;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ConcurrencyLimit that = (ConcurrencyLimit) o;
        return maxConcurrency == that.maxConcurrency && timeoutMillis == that.timeoutMillis &&
               policy.equals(that.policy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(policy, maxConcurrency, timeoutMillis);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("policy", policy)
                          .add("maxConcurrency", maxConcurrency)
                          .add("timeoutMillis", timeoutMillis)
                          .toString();
    }

    /**
     * Builds a {@link ConcurrencyLimit} instance using builder pattern.
     */
    public static class Builder {
        private int maxConcurrency;
        private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
        private Predicate<RequestContext> policy = requestContext -> true;

        /**
         * Sets the maximum number of concurrent active requests. {@code 0} to disable the limit.
         */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = validateMaxConcurrency(maxConcurrency == MAX_VALUE ? 0 : maxConcurrency);
            return this;
        }

        /**
         * Sets the amount of time until this decorator fails the request if the request was not
         *      delegated to the {@code delegate} before then.
         */
        public Builder timeout(long timeout, TimeUnit unit) {
            checkArgument(timeout >= 0, "timeout: %s (expected: >= 0)", timeout);
            requireNonNull(unit, "unit");
            this.timeoutMillis = unit.convert(timeout, MILLISECONDS);
            return this;
        }

        /**
         * Sets the predicate for which to apply the concurrency limit.
         */
        public Builder policy(Predicate<RequestContext> policy) {
            this.policy = requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Builds the {@code ConcurrencyLimit}.
         */
        public ConcurrencyLimit build() {
            return new ConcurrencyLimit(policy, maxConcurrency, timeoutMillis);
        }
    }
}
