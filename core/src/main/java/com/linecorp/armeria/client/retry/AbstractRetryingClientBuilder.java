/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Response;

/**
 * Builds a new {@link AbstractRetryingClient} or its decorator function.
 *
 * @param <O> the type of incoming {@link Response} of the {@link Client}
 */
public abstract class AbstractRetryingClientBuilder<O extends Response> {

    @Nullable
    private final RetryStrategy retryStrategy;

    @Nullable
    private final RetryStrategyWithContent<O> retryStrategyWithContent;

    private int maxTotalAttempts = Flags.defaultMaxTotalAttempts();
    private long responseTimeoutMillisForEachAttempt = Flags.defaultResponseTimeoutMillis();

    /**
     * Creates a new builder with the specified {@link RetryStrategy}.
     */
    protected AbstractRetryingClientBuilder(RetryStrategy retryStrategy) {
        this(requireNonNull(retryStrategy, "retryStrategy"), null);
    }

    /**
     * Creates a new builder with the specified {@link RetryStrategyWithContent}.
     */
    protected AbstractRetryingClientBuilder(RetryStrategyWithContent<O> retryStrategyWithContent) {
        this(null, requireNonNull(retryStrategyWithContent, "retryStrategyWithContent"));
    }

    private AbstractRetryingClientBuilder(@Nullable RetryStrategy retryStrategy,
                                          @Nullable RetryStrategyWithContent<O> retryStrategyWithContent) {
        this.retryStrategy = retryStrategy;
        this.retryStrategyWithContent = retryStrategyWithContent;
    }

    RetryStrategy retryStrategy() {
        checkState(retryStrategy != null, "retryStrategy is not set.");
        return retryStrategy;
    }

    RetryStrategyWithContent<O> retryStrategyWithContent() {
        checkState(retryStrategyWithContent != null, "retryStrategyWithContent is not set.");
        return retryStrategyWithContent;
    }

    /**
     * Sets the maximum number of total attempts. If unspecified, the value from
     * {@link Flags#defaultMaxTotalAttempts()} will be used.
     *
     * @return {@code this} to support method chaining.
     */
    public AbstractRetryingClientBuilder<O> maxTotalAttempts(int maxTotalAttempts) {
        checkArgument(maxTotalAttempts > 0,
                      "maxTotalAttempts: %s (expected: > 0)", maxTotalAttempts);
        this.maxTotalAttempts = maxTotalAttempts;
        return this;
    }

    int maxTotalAttempts() {
        return maxTotalAttempts;
    }

    /**
     * Sets the response timeout for each attempt in milliseconds.
     * When requests in {@link AbstractRetryingClient} are made,
     * corresponding responses are timed out by this value. {@code 0} disables the timeout.
     * It will be set by the default value in {@link Flags#defaultResponseTimeoutMillis()}, if the client
     * dose not specify.
     *
     * @return {@code this} to support method chaining.
     *
     * @see <a href="https://line.github.io/armeria/docs/client-retry#per-attempt-timeout">Per-attempt
     *      timeout</a>
     */
    public AbstractRetryingClientBuilder<O> responseTimeoutMillisForEachAttempt(
            long responseTimeoutMillisForEachAttempt) {
        checkArgument(responseTimeoutMillisForEachAttempt >= 0,
                      "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                      responseTimeoutMillisForEachAttempt);
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        return this;
    }

    long responseTimeoutMillisForEachAttempt() {
        return responseTimeoutMillisForEachAttempt;
    }

    /**
     * Sets the response timeout for each attempt. When requests in {@link AbstractRetryingClient} are made,
     * corresponding responses are timed out by this value. {@code 0} disables the timeout.
     *
     * @return {@code this} to support method chaining.
     *
     * @see <a href="https://line.github.io/armeria/docs/client-retry#per-attempt-timeout">Per-attempt
     *      timeout</a>
     */
    public AbstractRetryingClientBuilder<O> responseTimeoutForEachAttempt(
            Duration responseTimeoutForEachAttempt) {
        checkArgument(
                !requireNonNull(responseTimeoutForEachAttempt, "responseTimeoutForEachAttempt").isNegative(),
                "responseTimeoutForEachAttempt: %s (expected: >= 0)", responseTimeoutForEachAttempt);
        return responseTimeoutMillisForEachAttempt(responseTimeoutForEachAttempt.toMillis());
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("retryStrategy", retryStrategy)
                          .add("retryStrategyWithContent", retryStrategyWithContent)
                          .add("maxTotalAttempts", maxTotalAttempts)
                          .add("responseTimeoutMillisForEachAttempt", responseTimeoutMillisForEachAttempt);
    }
}
