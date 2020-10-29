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

    private static final RetryConfig DEFAULT_RETRY_CONFIG = RetryConfig.builder().build();

    @Nullable
    private final RetryRule retryRule;

    @Nullable
    private final RetryRuleWithContent<O> retryRuleWithContent;

    private final RetryConfigBuilder retryConfig = RetryConfig.builder();
    private RetryConfigMapping mapping = (ctx, req) -> DEFAULT_RETRY_CONFIG;

    /**
     * Creates a new builder with the specified {@link RetryRule}.
     */
    AbstractRetryingClientBuilder(RetryRule retryRule) {
        this(requireNonNull(retryRule, "retryRule"), null);
    }

    /**
     * Creates a new builder with the specified {@link RetryRuleWithContent}.
     */
    AbstractRetryingClientBuilder(RetryRuleWithContent<O> retryRuleWithContent) {
        this(null, requireNonNull(retryRuleWithContent, "retryRuleWithContent"));
    }

    private AbstractRetryingClientBuilder(@Nullable RetryRule retryRule,
                                          @Nullable RetryRuleWithContent<O> retryRuleWithContent) {
        this.retryRule = retryRule;
        this.retryRuleWithContent = retryRuleWithContent;
    }

    final RetryRule retryRule() {
        checkState(retryRule != null, "retryRule is not set.");
        return retryRule;
    }

    final RetryRuleWithContent<O> retryRuleWithContent() {
        checkState(retryRuleWithContent != null, "retryRuleWithContent is not set.");
        return retryRuleWithContent;
    }

    /**
     * Sets the maximum allowed number of total attempts. If unspecified, the value from
     * {@link Flags#defaultMaxTotalAttempts()} will be used.
     *
     * @return {@code this} to support method chaining.
     */
    public AbstractRetryingClientBuilder<O> maxTotalAttempts(int maxTotalAttempts) {
        checkArgument(maxTotalAttempts > 0,
                      "maxTotalAttempts: %s (expected: > 0)", maxTotalAttempts);
        retryConfig.maxTotalAttempts = maxTotalAttempts;
        mapping = (ctx, req) -> retryConfig.build();
        return this;
    }

    final int maxTotalAttempts() {
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
    public AbstractRetryingClientBuilder<O> responseTimeoutMillisForEachAttempt(
            long responseTimeoutMillisForEachAttempt) {
        checkArgument(responseTimeoutMillisForEachAttempt >= 0,
                      "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                      responseTimeoutMillisForEachAttempt);
        retryConfig.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        mapping = (ctx, req) -> retryConfig.build();
        return this;
    }

    final long responseTimeoutMillisForEachAttempt() {
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
    public AbstractRetryingClientBuilder<O> responseTimeoutForEachAttempt(
            Duration responseTimeoutForEachAttempt) {
        checkArgument(
                !requireNonNull(responseTimeoutForEachAttempt, "responseTimeoutForEachAttempt").isNegative(),
                "responseTimeoutForEachAttempt: %s (expected: >= 0)", responseTimeoutForEachAttempt);
        return responseTimeoutMillisForEachAttempt(responseTimeoutForEachAttempt.toMillis());
    }

    /**
     * Sets the {@link RetryConfigMapping} that returns a {@link RetryConfig} for a given context/request.
     */
    public AbstractRetryingClientBuilder<O> mapping(RetryConfigMapping mapping) {
        this.mapping = mapping;
        return this;
    }

    final RetryConfigMapping mapping() {
        return mapping;
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    final ToStringHelper toStringHelper() {
        return MoreObjects
                .toStringHelper(this)
                .omitNullValues()
                .add("retryRule", retryRule)
                .add("retryRuleWithContent", retryRuleWithContent)
                .add("maxTotalAttempts", retryConfig.maxTotalAttempts)
                .add("responseTimeoutMillisForEachAttempt", retryConfig.responseTimeoutMillisForEachAttempt);
    }
}
