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

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.linecorp.armeria.client.ClientRequestContext;

/**
 * Builds a {@link ConcurrencyLimit} instance using builder pattern.
 */
public class ConcurrencyLimitBuilder {
    private static final long DEFAULT_TIMEOUT_MILLIS = 10000L;

    private int maxConcurrency;
    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    private Predicate<ClientRequestContext> policy = requestContext -> true;

    /**
     * Sets the maximum number of concurrent active requests. {@code 0} to disable the limit.
     */
    public ConcurrencyLimitBuilder maxConcurrency(int maxConcurrency) {
        this.maxConcurrency = validateMaxConcurrency(maxConcurrency == MAX_VALUE ? 0 : maxConcurrency);
        return this;
    }

    /**
     * Sets the amount of time until this decorator fails the request if the request was not
     *      delegated to the {@code delegate} before then.
     */
    public ConcurrencyLimitBuilder timeout(long timeout, TimeUnit unit) {
        checkArgument(timeout >= 0, "timeout: %s (expected: >= 0)", timeout);
        requireNonNull(unit, "unit");
        this.timeoutMillis = unit.convert(timeout, MILLISECONDS);
        return this;
    }

    /**
     * Sets the predicate for which to apply the concurrency limit.
     */
    public ConcurrencyLimitBuilder policy(Predicate<ClientRequestContext> policy) {
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
