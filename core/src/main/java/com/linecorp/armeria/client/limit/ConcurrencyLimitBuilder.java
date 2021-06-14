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
import java.util.function.Predicate;

import com.linecorp.armeria.client.ClientRequestContext;

/**
 * Builds a {@link ConcurrencyLimit} instance using builder pattern.
 */
public final class ConcurrencyLimitBuilder {
    static final long DEFAULT_TIMEOUT_MILLIS = 10000L;

    private final int maxConcurrency;
    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    private Predicate<? super ClientRequestContext> predicate = requestContext -> true;

    ConcurrencyLimitBuilder(int maxConcurrency) {
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
     * Sets the predicate for which to apply the concurrency limit.
     */
    public ConcurrencyLimitBuilder predicate(Predicate<? super ClientRequestContext> predicate) {
        this.predicate = requireNonNull(predicate, "predicate");
        return this;
    }

    /**
     * Returns a newly-created the {@link ConcurrencyLimit} based on the properties of this builder.
     */
    public ConcurrencyLimit build() {
        return new ConcurrencyLimit(predicate, maxConcurrency, timeoutMillis);
    }
}
