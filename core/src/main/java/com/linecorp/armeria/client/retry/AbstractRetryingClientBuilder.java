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
    private final RetryConfigBuilder<O> retryConfig;

    @Nullable
    private final RetryConfigMapping<O> mapping;

    /**
     * Creates a new builder with the specified {@link RetryConfig}.
     */
    AbstractRetryingClientBuilder(RetryConfig<O> retryConfig) {
        this.retryConfig = requireNonNull(retryConfig, "retryConfig").toBuilder();
        mapping = null;
    }

    /**
     * Creates a new builder with the specified {@link RetryConfigMapping}.
     */
    AbstractRetryingClientBuilder(RetryConfigMapping<O> mapping) {
        this.mapping = requireNonNull(mapping, "mapping");
        retryConfig = null;
    }

    final RetryConfigMapping<O> mapping() {
        if (mapping == null) {
            final RetryConfig<O> config = retryConfig();
            return (ctx, req) -> config;
        }
        return mapping;
    }

    @Nullable
    final RetryConfig<O> retryConfig() {
        if (retryConfig == null) {
            return null;
        }
        return retryConfig.build();
    }

    /**
     * Sets the maximum allowed number of total attempts. If unspecified, the value from
     * {@link Flags#defaultMaxTotalAttempts()} will be used.
     *
     * @return {@code this} to support method chaining.
     *
     * @deprecated Use {@link RetryConfigBuilder}::maxTotalAttempts() instead.
     */
    @Deprecated
    public AbstractRetryingClientBuilder<O> maxTotalAttempts(int maxTotalAttempts) {
        checkState(retryConfig != null, "You are using a RetryConfigMapping. You cannot set maxTotalAttempts.");
        checkArgument(maxTotalAttempts > 0,
                      "maxTotalAttempts: %s (expected: > 0)", maxTotalAttempts);
        retryConfig.maxTotalAttempts(maxTotalAttempts);
        return this;
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
     * @see <a href="https://armeria.dev/docs/client-retry#per-attempt-timeout">Per-attempt timeout</a>
     *
     * @deprecated Use {@link RetryConfigBuilder}::responseTimeoutMillisForEachAttempt() instead.
     */
    @Deprecated
    public AbstractRetryingClientBuilder<O> responseTimeoutMillisForEachAttempt(
            long responseTimeoutMillisForEachAttempt) {
        checkState(retryConfig != null,
                   "You are using a RetryConfigMapping. You cannot set responseTimeoutMillisForEachAttempt.");
        checkArgument(responseTimeoutMillisForEachAttempt >= 0,
                      "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                      responseTimeoutMillisForEachAttempt);
        retryConfig.responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt);
        return this;
    }

    /**
     * Sets the response timeout for each attempt. When requests in {@link AbstractRetryingClient} are made,
     * corresponding responses are timed out by this value. {@code 0} disables the timeout.
     *
     * @return {@code this} to support method chaining.
     *
     * @see <a href="https://armeria.dev/docs/client-retry#per-attempt-timeout">Per-attempt timeout</a>
     *
     * @deprecated Use {@link RetryConfigBuilder}::responseTimeoutForEachAttempt() instead.
     */
    @Deprecated
    public AbstractRetryingClientBuilder<O> responseTimeoutForEachAttempt(
            Duration responseTimeoutForEachAttempt) {
        checkState(
                retryConfig != null,
                "You are using a RetryConfigMapping, so you cannot set responseTimeoutForEachAttempt.");
        checkArgument(
                !requireNonNull(responseTimeoutForEachAttempt, "responseTimeoutForEachAttempt").isNegative(),
                "responseTimeoutForEachAttempt: %s (expected: >= 0)", responseTimeoutForEachAttempt);
        return responseTimeoutMillisForEachAttempt(responseTimeoutForEachAttempt.toMillis());
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    final ToStringHelper toStringHelper() {
        if (retryConfig == null) {
            return MoreObjects.toStringHelper(this).add("mapping", mapping);
        }
        return retryConfig.toStringHelper();
    }
}
