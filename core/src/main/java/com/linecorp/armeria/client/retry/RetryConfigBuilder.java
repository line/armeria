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
import static com.google.common.base.Preconditions.checkNotNull;

import java.time.Duration;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Response;

/**
 * Builds a {@link RetryConfig}.
 * A {@link RetryConfig} instance encapsulates the used {@link RetryRule}, maxTotalAttempts,
 * and responseTimeoutMillisForEachAttempt.
 */
public final class RetryConfigBuilder<T extends Response> {
    int maxTotalAttempts = Flags.defaultMaxTotalAttempts();
    long responseTimeoutMillisForEachAttempt = Flags.defaultResponseTimeoutMillis();
    @Nullable final RetryRule retryRule;
    @Nullable final RetryRuleWithContent<T> retryRuleWithContent;
    final int maxContentLength;

    private RetryConfigBuilder() {
        throw new IllegalStateException("RetryConfigBuilder must have a rule.");
    }

    /**
     * Returns a {@link RetryConfigBuilder} with this {@link RetryRule}.
     */
    public RetryConfigBuilder(RetryRule retryRule) {
        this.retryRule = checkNotNull(retryRule);
        retryRuleWithContent = null;
        maxContentLength = 0;
    }

    /**
     * Returns a {@link RetryConfigBuilder} with this {@link RetryRuleWithContent}.
     */
    public RetryConfigBuilder(RetryRuleWithContent<T> retryRuleWithContent) {
        this(retryRuleWithContent, Integer.MAX_VALUE);
    }

    /**
     * Returns a {@link RetryConfigBuilder} with this {@link RetryRuleWithContent} and maxContentLength.
     */
    public RetryConfigBuilder(RetryRuleWithContent<T> retryRuleWithContent, int maxContentLength) {
        retryRule = null;
        this.retryRuleWithContent = checkNotNull(retryRuleWithContent);
        checkArgument(maxContentLength > 0,
                      "maxContentLength: %s (expected: > 0)", maxContentLength);
        this.maxContentLength = maxContentLength;
    }

    /**
     * Sets maxTotalAttempts.
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
     * Sets responseTimeoutMillisForEachAttempt.
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
     * Sets responseTimeoutMillisForEachAttempt by converting responseTimeoutForEachAttempt to millis.
     */
    public RetryConfigBuilder<T> responseTimeoutForEachAttempt(Duration responseTimeoutMillisForEachAttempt) {
        final long millis = responseTimeoutMillisForEachAttempt.toMillis();
        checkArgument(
                millis >= 0,
                "responseTimeoutForEachAttempt.toMillis(): %s (expected: >= 0)",
                millis);
        this.responseTimeoutMillisForEachAttempt = millis;
        return this;
    }

    /**
     * Builds a {@link RetryConfig} from this builder's values and returns it.
     */
    public RetryConfig<T> build() {
        if (retryRule != null) {
            return new RetryConfig<>(retryRule, maxTotalAttempts, responseTimeoutMillisForEachAttempt);
        } else if (retryRuleWithContent != null) {
            return new RetryConfig<>(
                    retryRuleWithContent,
                    maxContentLength,
                    maxTotalAttempts,
                    responseTimeoutMillisForEachAttempt);
        } else {
            throw new IllegalStateException("RetryConfigBuilder must have a rule.");
        }
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
