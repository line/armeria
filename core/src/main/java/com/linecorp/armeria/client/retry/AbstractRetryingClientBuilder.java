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
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Response;

/**
 * Builds a new {@link AbstractRetryingClient} or its decorator function.
 *
 * @param <T> the type of incoming {@link Response} of the {@link Client}
 */
public abstract class AbstractRetryingClientBuilder<T extends Response> {

    @Nullable private final RetryConfigBuilder<T> retryConfig;
    private RetryConfigMapping<T> mapping;

    /**
     * Creates a new builder with the specified {@link RetryRule}.
     */
    AbstractRetryingClientBuilder(RetryRule retryRule) {
        retryConfig = RetryConfig.builder(requireNonNull(retryRule, "retryRule"));
        mapping = (ctx, req) -> retryConfig.build();
    }

    /**
     * Creates a new builder with the specified {@link RetryRuleWithContent}.
     */
    AbstractRetryingClientBuilder(RetryRuleWithContent<T> retryRuleWithContent) {
        retryConfig = RetryConfig.builder(requireNonNull(retryRuleWithContent, "retryRuleWithContent"));
        mapping = (ctx, req) -> retryConfig.build();
    }

    /**
     * Creates a new builder with the specified {@link RetryRuleWithContent}.
     */
    AbstractRetryingClientBuilder(RetryRuleWithContent<T> retryRuleWithContent, int maxContentLength) {
        retryConfig = RetryConfig.builder(
                requireNonNull(retryRuleWithContent, "retryRuleWithContent"),
                maxContentLength);
        mapping = (ctx, req) -> retryConfig.build();
    }

    /**
     * Creates a new builder with the specified {@link RetryConfigMapping}.
     */
    AbstractRetryingClientBuilder(RetryConfigMapping<T> mapping) {
        this.mapping = checkNotNull(mapping);
        retryConfig = null;
    }

    final RetryRule retryRule() {
        checkState(
                retryConfig != null,
                "You are using a RetryConfigMapping, so you do not have one retryRule.");
        checkState(retryConfig.retryRule != null, "retryRule is not set.");
        return retryConfig.retryRule;
    }

    final RetryRuleWithContent<T> retryRuleWithContent() {
        checkState(
                retryConfig != null,
                "You are using a RetryConfigMapping, so you do not have one retryRuleWithContent.");
        checkState(retryConfig.retryRuleWithContent != null, "retryRuleWithContent is not set.");
        return retryConfig.retryRuleWithContent;
    }

    final RetryConfigMapping<T> mapping() {
        return mapping;
    }

    /**
     * Sets the maximum allowed number of total attempts. If unspecified, the value from
     * {@link Flags#defaultMaxTotalAttempts()} will be used.
     *
     * @return {@code this} to support method chaining.
     */
    public AbstractRetryingClientBuilder<T> maxTotalAttempts(int maxTotalAttempts) {
        checkState(
                retryConfig != null,
                "You are using a RetryConfigMapping, so you cannot set maxTotalAttempts.");
        checkArgument(maxTotalAttempts > 0,
                      "maxTotalAttempts: %s (expected: > 0)", maxTotalAttempts);
        retryConfig.maxTotalAttempts = maxTotalAttempts;
        mapping = (ctx, req) -> retryConfig.build();
        return this;
    }

    final int maxTotalAttempts() {
        checkState(
                retryConfig != null,
                "You are using a RetryConfigMapping, so you cannot get maxTotalAttempts.");
        return retryConfig.maxTotalAttempts;
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
     */
    public AbstractRetryingClientBuilder<T> responseTimeoutMillisForEachAttempt(
            long responseTimeoutMillisForEachAttempt) {
        checkState(
                retryConfig != null,
                "You are using a RetryConfigMapping, so you cannot set responseTimeoutMillisForEachAttempt.");
        checkArgument(responseTimeoutMillisForEachAttempt >= 0,
                      "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                      responseTimeoutMillisForEachAttempt);
        retryConfig.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        mapping = (ctx, req) -> retryConfig.build();
        return this;
    }

    final long responseTimeoutMillisForEachAttempt() {
        checkState(
                retryConfig != null,
                "You are using a RetryConfigMapping, so you cannot get responseTimeoutMillisForEachAttempt.");
        return retryConfig.responseTimeoutMillisForEachAttempt;
    }

    /**
     * Sets the response timeout for each attempt. When requests in {@link AbstractRetryingClient} are made,
     * corresponding responses are timed out by this value. {@code 0} disables the timeout.
     *
     * @return {@code this} to support method chaining.
     *
     * @see <a href="https://armeria.dev/docs/client-retry#per-attempt-timeout">Per-attempt timeout</a>
     */
    public AbstractRetryingClientBuilder<T> responseTimeoutForEachAttempt(
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
        checkState(
                retryConfig != null,
                "You are using a RetryConfigMapping, so you cannot get a string representation.");
        return retryConfig.toStringHelper();
    }
}
