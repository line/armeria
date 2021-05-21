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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;

/**
 * Concurrency settings that limits the concurrent number of active requests.
 */
public final class ConcurrencyLimit {
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

    private final Predicate<ClientRequestContext> policy;
    private final int maxConcurrency;
    private final long timeoutMillis;
    private final AtomicInteger numActiveRequests = new AtomicInteger();

    ConcurrencyLimit(Predicate<ClientRequestContext> policy, int maxConcurrency, long timeoutMillis) {
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
     * Returns the value of the {@code "timeout"} in milliseconds.
     */
    public long timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Checks if the concurrency control should be enforced for the given {@link ClientRequestContext}.
     */
    public boolean shouldLimit(ClientRequestContext requestContext) {
        requireNonNull(requestContext, "requestContext");
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
        if (!(o instanceof ConcurrencyLimit)) {
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
}
