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
     * Returns a new {@link RetryConfigBuilder} with the default values from Flags.
     * Uses a {@code RetryRule}.
     */
    public static RetryConfigBuilder<HttpResponse> builder(RetryRule retryRule) {
        return new RetryConfigBuilder<>(retryRule);
    }

    /**
     * Returns a new {@link RetryConfigBuilder} with the default values from Flags.
     * Uses a {@code RetryRuleWithContent<HttpResponse>}.
     */
    public static RetryConfigBuilder<HttpResponse> builder(
            RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return new RetryConfigBuilder<>(retryRuleWithContent);
    }

    /**
     * Returns a new {@link RetryConfigBuilder} with the default values from Flags.
     * Uses a {@code RetryRule}.
     */
    public static RetryConfigBuilder<RpcResponse> builderForRpc(RetryRule retryRule) {
        return new RetryConfigBuilder<>(retryRule);
    }

    /**
     * Returns a new {@link RetryConfigBuilder} with the default values from Flags.
     * Uses a {@code RetryRuleWithContent<RpcResponse>}.
     */
    public static RetryConfigBuilder<RpcResponse> builderForRpc(
            RetryRuleWithContent<RpcResponse> retryRuleWithContent) {
        return new RetryConfigBuilder<>(retryRuleWithContent);
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
        checkArguments(maxTotalAttempts, responseTimeoutMillisForEachAttempt);
        this.retryRule = requireNonNull(retryRule, "retryRule");
        this.maxTotalAttempts = maxTotalAttempts;
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        maxContentLength = 0;
        retryRuleWithContent = null;
        fromRetryRuleWithContent = null;
    }

    RetryConfig(
            RetryRuleWithContent<T> retryRuleWithContent,
            int maxContentLength,
            int maxTotalAttempts,
            long responseTimeoutMillisForEachAttempt) {
        checkArguments(maxTotalAttempts, responseTimeoutMillisForEachAttempt);
        this.maxContentLength = maxContentLength;
        this.retryRuleWithContent = requireNonNull(retryRuleWithContent, "retryRuleWithContent");
        fromRetryRuleWithContent = RetryRuleUtil.fromRetryRuleWithContent(retryRuleWithContent);
        this.maxTotalAttempts = maxTotalAttempts;
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        retryRule = null;
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
     * Returns the {@link RetryRule} used by {@link RetryingClient} using this config, could be null.
     */
    @Nullable
    public RetryRule retryRule() {
        return retryRule;
    }

    /**
     * Returns the {@link RetryRuleWithContent} used by {@link RetryingClient} using this config, could be null.
     */
    @Nullable
    public RetryRuleWithContent<T> retryRuleWithContent() {
        return retryRuleWithContent;
    }

    /**
     * Returns config's {@code maxContentLength}, which is non-zero only if
     * a {@link RetryRuleWithContent} is used.
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
            logger.warn("A RetryRuleWithContent is being generated from a RetryRule. " +
                    "You are probably using a RetryRule with a RetryingRpcClient. " +
                    "Please ensure that this is intentional.");
            fromRetryRule = RetryRuleUtil.fromRetryRule(retryRule);
        }
        return fromRetryRule;
    }
}
