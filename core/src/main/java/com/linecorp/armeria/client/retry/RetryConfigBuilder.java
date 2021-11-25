/*
 * Copyright 2020 LINE Corporation
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
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Builds a {@link RetryConfig}.
 * A {@link RetryConfig} instance encapsulates the used {@link RetryRule}, {@code maxTotalAttempts},
 * and {@code responseTimeoutMillisForEachAttempt}.
 */
public final class RetryConfigBuilder<T extends Response> {
    private int maxTotalAttempts = Flags.defaultMaxTotalAttempts();
    private long responseTimeoutMillisForEachAttempt = Flags.defaultResponseTimeoutMillis();
    private int maxContentLength;

    @Nullable
    private final RetryRule retryRule;
    @Nullable
    private final RetryRuleWithContent<T> retryRuleWithContent;

    /**
     * Creates a {@link RetryConfigBuilder} with this {@link RetryRule}.
     */
    RetryConfigBuilder(RetryRule retryRule) {
        this.retryRule = requireNonNull(retryRule, "retryRule");
        retryRuleWithContent = null;
        maxContentLength = 0;
    }

    /**
     * Creates a {@link RetryConfigBuilder} with this {@link RetryRuleWithContent}.
     */
    RetryConfigBuilder(RetryRuleWithContent<T> retryRuleWithContent) {
        retryRule = null;
        this.retryRuleWithContent = requireNonNull(retryRuleWithContent, "retryRuleWithContent");
        maxContentLength = Integer.MAX_VALUE;
    }

    /**
     * Sets the specified {@code maxContentLength} to be used with a {@link RetryRuleWithContent}.
     */
    public RetryConfigBuilder<T> maxContentLength(int maxContentLength) {
        requireNonNull(retryRuleWithContent, "retryRuleWithContent");
        checkArgument(maxContentLength > 0,
                      "maxContentLength: %s (expected: > 0)", maxContentLength);
        this.maxContentLength = maxContentLength;
        return this;
    }

    /**
     * Sets the specified {@code maxTotalAttempts}.
     */
    public RetryConfigBuilder<T> maxTotalAttempts(int maxTotalAttempts) {
        checkArgument(
                maxTotalAttempts > 0,
                "maxTotalAttempts: %s (expected: > 0)",
                maxTotalAttempts);
        this.maxTotalAttempts = maxTotalAttempts;
        return this;
    }

    /**
     * Sets the specified {@code responseTimeoutMillisForEachAttempt}.
     */
    public RetryConfigBuilder<T> responseTimeoutMillisForEachAttempt(long responseTimeoutMillisForEachAttempt) {
        checkArgument(
                responseTimeoutMillisForEachAttempt >= 0,
                "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                responseTimeoutMillisForEachAttempt);
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        return this;
    }

    /**
     * Sets the specified {@link Duration} by converting responseTimeoutForEachAttempt to millis.
     */
    public RetryConfigBuilder<T> responseTimeoutForEachAttempt(Duration responseTimeoutMillisForEachAttempt) {
        final long millis =
                requireNonNull(responseTimeoutMillisForEachAttempt, "responseTimeoutMillisForEachAttempt")
                        .toMillis();
        checkArgument(
                millis >= 0,
                "responseTimeoutForEachAttempt.toMillis(): %s (expected: >= 0)",
                millis);
        this.responseTimeoutMillisForEachAttempt = millis;
        return this;
    }

    /**
     * Returns a newly-created {@link RetryConfig} from this {@link RetryConfigBuilder}'s values.
     */
    public RetryConfig<T> build() {
        if (retryRule != null) {
            return new RetryConfig<>(retryRule, maxTotalAttempts, responseTimeoutMillisForEachAttempt);
        }
        assert retryRuleWithContent != null;
        return new RetryConfig<>(
                retryRuleWithContent,
                maxContentLength,
                maxTotalAttempts,
                responseTimeoutMillisForEachAttempt);
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    ToStringHelper toStringHelper() {
        return MoreObjects
                .toStringHelper(this)
                .omitNullValues()
                .add("retryRule", retryRule)
                .add("retryRuleWithContent", retryRuleWithContent)
                .add("maxTotalAttempts", maxTotalAttempts)
                .add("responseTimeoutMillisForEachAttempt", responseTimeoutMillisForEachAttempt)
                .add("maxContentLength", maxContentLength);
    }
}
