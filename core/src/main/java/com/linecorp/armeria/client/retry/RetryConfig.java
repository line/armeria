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

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcResponse;

/**
 * Holds retry config used by a {@link RetryingClient}.
 * A {@link RetryConfig} instance encapsulates the used {@link RetryRule}, {@code maxTotalAttempts},
 * and {@code responseTimeoutMillisForEachAttempt}.
 */
public final class RetryConfig<T extends Response> {

    private static final Logger logger = LoggerFactory.getLogger(RetryConfig.class);

    /**
     * Returns a new {@link RetryConfigBuilder} with the specified {@link RetryRule}.
     */
    public static RetryConfigBuilder<HttpResponse> builder(RetryRule retryRule) {
        return builder0(retryRule);
    }

    /**
     * Returns a new {@link RetryConfigBuilder} with the specified {@link RetryRuleWithContent}.
     */
    public static RetryConfigBuilder<HttpResponse> builder(
            RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return builder0(retryRuleWithContent);
    }

    /**
     * Returns a new {@link RetryConfigBuilder} with the specified {@link RetryRule}.
     */
    public static RetryConfigBuilder<RpcResponse> builderForRpc(RetryRule retryRule) {
        return builder0(retryRule);
    }

    /**
     * Returns a new {@link RetryConfigBuilder} with the specified {@link RetryRuleWithContent}.
     */
    public static RetryConfigBuilder<RpcResponse> builderForRpc(
            RetryRuleWithContent<RpcResponse> retryRuleWithContent) {
        return builder0(retryRuleWithContent);
    }

    static <T extends Response> RetryConfigBuilder<T> builder0(RetryRule retryRule) {
        return new RetryConfigBuilder<>(retryRule);
    }

    static <T extends Response> RetryConfigBuilder<T> builder0(
            RetryRuleWithContent<T> retryRuleWithContent) {
        return new RetryConfigBuilder<>(retryRuleWithContent);
    }

    private final int maxTotalAttempts;
    private final long responseTimeoutMillisForEachAttempt;
    private final int maxContentLength;

    @Nullable
    private final RetryRule retryRule;
    @Nullable
    private final RetryRuleWithContent<T> retryRuleWithContent;
    @Nullable
    private final RetryRule fromRetryRuleWithContent;
    @Nullable
    private RetryRuleWithContent<T> fromRetryRule;

    RetryConfig(RetryRule retryRule, int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        this(requireNonNull(retryRule, "retryRule"), null,
                maxTotalAttempts, responseTimeoutMillisForEachAttempt, 0);
        checkArguments(maxTotalAttempts, responseTimeoutMillisForEachAttempt);
    }

    RetryConfig(
            RetryRuleWithContent<T> retryRuleWithContent,
            int maxContentLength,
            int maxTotalAttempts,
            long responseTimeoutMillisForEachAttempt) {
        this(null, requireNonNull(retryRuleWithContent, "retryRuleWithContent"),
                maxTotalAttempts, responseTimeoutMillisForEachAttempt, maxContentLength);
    }

    private RetryConfig(
            @Nullable RetryRule retryRule,
            @Nullable RetryRuleWithContent<T> retryRuleWithContent,
            int maxTotalAttempts,
            long responseTimeoutMillisForEachAttempt,
            int maxContentLength) {
        checkArguments(maxTotalAttempts, responseTimeoutMillisForEachAttempt);
        this.retryRule = retryRule;
        this.retryRuleWithContent = retryRuleWithContent;
        this.maxTotalAttempts = maxTotalAttempts;
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        this.maxContentLength = maxContentLength;
        if (retryRuleWithContent == null) {
            fromRetryRuleWithContent = null;
        } else {
            fromRetryRuleWithContent = RetryRuleUtil.fromRetryRuleWithContent(retryRuleWithContent);
        }
    }

    private static void checkArguments(int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        checkArgument(
                maxTotalAttempts > 0,
                "maxTotalAttempts: %s (expected: > 0)",
                maxTotalAttempts);
        checkArgument(
                responseTimeoutMillisForEachAttempt >= 0,
                "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                responseTimeoutMillisForEachAttempt);
    }

    /**
     * Converts this {@link RetryConfig} to a mutable {@link RetryConfigBuilder}.
     */
    public RetryConfigBuilder<T> toBuilder() {
        final RetryConfigBuilder<T> builder =
                retryRuleWithContent != null ?
                builder0(retryRuleWithContent).maxContentLength(maxContentLength) : builder0(retryRule);
        return builder
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt);
    }

    /**
     * Returns the maximum allowed number of total attempts made by a {@link RetryingClient}.
     */
    public int maxTotalAttempts() {
        return maxTotalAttempts;
    }

    /**
     * Returns the response timeout for each attempt in milliseconds.
     * When requests in {@link RetryingClient} are made,
     * corresponding responses are timed out by this value.
     */
    public long responseTimeoutMillisForEachAttempt() {
        return responseTimeoutMillisForEachAttempt;
    }

    /**
     * Returns the {@link RetryRule} which was specified with {@link RetryConfig#builder(RetryRule)}.
     */
    @Nullable
    public RetryRule retryRule() {
        return retryRule;
    }

    /**
     * Returns the {@link RetryRuleWithContent} which was specified with
     * {@link RetryConfig#builder(RetryRuleWithContent)}.
     */
    @Nullable
    public RetryRuleWithContent<T> retryRuleWithContent() {
        return retryRuleWithContent;
    }

    /**
     * Returns the {@code maxContentLength}, which is non-zero only if a {@link RetryRuleWithContent} is used.
     */
    public int maxContentLength() {
        return maxContentLength;
    }

    /**
     * Returns whether a {@link RetryRuleWithContent} is being used.
     */
    public boolean needsContentInRule() {
        return retryRuleWithContent != null;
    }

    /**
     * Returns whether the associated {@link RetryRule} or {@link RetryRuleWithContent} requires
     * response trailers.
     */
    public boolean requiresResponseTrailers() {
        return needsContentInRule() ?
               retryRuleWithContent().requiresResponseTrailers() : retryRule().requiresResponseTrailers();
    }

    /**
     * Returns the {@link RetryRuleWithContent} converted from the {@link RetryRule} of this config.
     */
    RetryRule fromRetryRuleWithContent() {
        requireNonNull(retryRuleWithContent, "retryRuleWithContent");
        requireNonNull(fromRetryRuleWithContent, "fromRetryRuleWithContent");
        return fromRetryRuleWithContent;
    }

    /**
     * Returns the {@link RetryRule} converted from the {@link RetryRuleWithContent} of this config.
     */
    RetryRuleWithContent<T> fromRetryRule() {
        requireNonNull(retryRule, "retryRule");
        if (fromRetryRule == null) {
            logger.warn(
                    "A RetryRuleWithContent is being generated from a RetryRule. " +
                    "You are probably using a RetryRule with a RetryingRpcClient. " +
                    "Please ensure that this is intentional.");
            fromRetryRule = RetryRuleUtil.fromRetryRule(retryRule);
        }
        return fromRetryRule;
    }
}
