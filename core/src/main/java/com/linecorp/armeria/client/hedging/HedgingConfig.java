/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.hedging;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;


public final class HedgingConfig<T extends Response> {
    private static final Logger logger = LoggerFactory.getLogger(HedgingConfig.class);

    public static HedgingConfigBuilder<HttpResponse> builder(HedgingRule hedgingRule, long initialHedgingDelayMillis) {
        return builder0(hedgingRule, initialHedgingDelayMillis);
    }

    public static HedgingConfigBuilder<HttpResponse> builder(
            HedgingRuleWithContent<HttpResponse> hedgingRuleWithContent, long initialHedgingDelayMillis) {
        return builder0(hedgingRuleWithContent, initialHedgingDelayMillis);
    }

    public static HedgingConfigBuilder<RpcResponse> builderForRpc(HedgingRule hedgingRule, long initialHedgingDelayMillis) {
        return builder0(hedgingRule, initialHedgingDelayMillis);
    }

    public static HedgingConfigBuilder<RpcResponse> builderForRpc(
            HedgingRuleWithContent<RpcResponse> hedgingRuleWithContent, long initialHedgingDelayMillis) {
        return builder0(hedgingRuleWithContent, initialHedgingDelayMillis);
    }

    static <T extends Response> HedgingConfigBuilder<T> builder0(HedgingRule hedgingRule, long initialHedgingDelayMillis) {
        return new HedgingConfigBuilder<>(hedgingRule, initialHedgingDelayMillis);
    }

    static <T extends Response> HedgingConfigBuilder<T> builder0(
            HedgingRuleWithContent<T> hedgingRuleWithContent, long initialHedgingDelayMillis) {
        return new HedgingConfigBuilder<>(hedgingRuleWithContent, initialHedgingDelayMillis);
    }

    private final int maxTotalAttempts;
    private final long responseTimeoutMillisForEachAttempt;
    private final int maxContentLength;

    private final long initialHedgingDelayMillis;

    @Nullable
    private final HedgingRule hedgingRule;
    @Nullable
    private final HedgingRuleWithContent<T> hedgingRuleWithContent;
    @Nullable
    private final HedgingRule fromHedgingRuleWithContent;
    @Nullable
    private HedgingRuleWithContent<T> fromHedgingRule;

    HedgingConfig(HedgingRule hedgingRule, int maxTotalAttempts, long responseTimeoutMillisForEachAttempt,
                  long initialHedgingDelayMillis) {
        this(requireNonNull(hedgingRule, "hedgingRule"),null, maxTotalAttempts,
            responseTimeoutMillisForEachAttempt, 0, initialHedgingDelayMillis);
        checkArguments(maxTotalAttempts, responseTimeoutMillisForEachAttempt, initialHedgingDelayMillis);
    }

    HedgingConfig(
            HedgingRuleWithContent<T> hedgingRuleWithContent,
            int maxContentLength,
            int maxTotalAttempts,
            long responseTimeoutMillisForEachAttempt,
            long initialHedgingDelayMillis) {
        this(null, requireNonNull(hedgingRuleWithContent, "hedgingRuleWithContent"),
             maxTotalAttempts, responseTimeoutMillisForEachAttempt, maxContentLength, initialHedgingDelayMillis);
    }

    private HedgingConfig(
            @Nullable HedgingRule hedgingRule,
            @Nullable HedgingRuleWithContent<T> hedgingRuleWithContent,
            int maxTotalAttempts,
            long responseTimeoutMillisForEachAttempt,
            int maxContentLength, long initialHedgingDelayMillis) {
        checkArguments(maxTotalAttempts, responseTimeoutMillisForEachAttempt, initialHedgingDelayMillis);
        this.hedgingRule = hedgingRule;
        this.hedgingRuleWithContent = hedgingRuleWithContent;
        this.maxTotalAttempts = maxTotalAttempts;
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        this.maxContentLength = maxContentLength;
        this.initialHedgingDelayMillis = initialHedgingDelayMillis;

        if (hedgingRuleWithContent == null) {
            fromHedgingRuleWithContent = null;
        } else {
            fromHedgingRuleWithContent = HedgingRuleUtil.fromHedgingRuleWithContent(hedgingRuleWithContent);
        }
    }

    private static void checkArguments(int maxTotalAttempts, long responseTimeoutMillisForEachAttempt, long initialHedgingDelayMillis) {
        checkArgument(
                maxTotalAttempts > 0,
                "maxTotalAttempts: %s (expected: > 0)",
                maxTotalAttempts);
        checkArgument(
                responseTimeoutMillisForEachAttempt >= 0,
                "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                responseTimeoutMillisForEachAttempt);
        checkArgument(
                initialHedgingDelayMillis >= 0,
                "initialHedgingDelayMillis: %s (expected: >= 0)",
                initialHedgingDelayMillis);
    }

    public HedgingConfigBuilder<T> toBuilder() {
        final HedgingConfigBuilder<T> builder;
        if (hedgingRuleWithContent != null) {
            builder = builder0(hedgingRuleWithContent, initialHedgingDelayMillis).maxContentLength(
                    maxContentLength);
        } else {
            assert hedgingRule != null;
            builder = builder0(hedgingRule, initialHedgingDelayMillis);
        }
        return builder
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt);
    }

    public int maxTotalAttempts() {
        return maxTotalAttempts;
    }

    public long responseTimeoutMillisForEachAttempt() {
        return responseTimeoutMillisForEachAttempt;
    }

    public long initialHedgingDelayMillis() {
        return initialHedgingDelayMillis;
    }

    @Nullable
    public HedgingRule hedgingRule() {
        return hedgingRule;
    }

    @Nullable
    public HedgingRuleWithContent<T> hedgingRuleWithContent() {
        return hedgingRuleWithContent;
    }

    public int maxContentLength() {
        return maxContentLength;
    }

    public boolean needsContentInRule() {
        return hedgingRuleWithContent != null;
    }

    public boolean requiresResponseTrailers() {
        if (needsContentInRule()) {
            final HedgingRuleWithContent<T> rule = hedgingRuleWithContent();
            assert rule != null;
            return rule.requiresResponseTrailers();
        } else {
            final HedgingRule rule = hedgingRule();
            assert rule != null;
            return rule.requiresResponseTrailers();
        }
    }

    HedgingRule fromHedgingRuleWithContent() {
        requireNonNull(hedgingRuleWithContent, "hedgingRuleWithContent");
        requireNonNull(fromHedgingRuleWithContent, "fromHedgingRuleWithContent");
        return fromHedgingRuleWithContent;
    }

    HedgingRuleWithContent<T> fromHedgingRule() {
        requireNonNull(hedgingRule, "hedgingRule");
        if (fromHedgingRule == null) {
            logger.warn(
                    "A HedgingRuleWithContent is being generated from a HedgingRule. " +
                    "You are probably using a HedgingRule with a HedgingRpcClient. " +
                    "Please ensure that this is intentional.");
            fromHedgingRule = HedgingRuleUtil.fromHedgingRule(hedgingRule);
        }
        return fromHedgingRule;
    }
}
