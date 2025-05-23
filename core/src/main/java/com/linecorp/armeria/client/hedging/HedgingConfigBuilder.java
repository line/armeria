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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;


public final class HedgingConfigBuilder<T extends Response> {
    private int maxTotalAttempts = Flags.defaultMaxTotalAttempts();
    private long responseTimeoutMillisForEachAttempt = Flags.defaultResponseTimeoutMillis();
    private int maxContentLength;
    private long initialHedgingDelayMillis = HedgingDecision.NO_HEDGING_DELAY_MILLIS;

    @Nullable
    private final HedgingRule hedgingRule;
    @Nullable
    private final HedgingRuleWithContent<T> hedgingRuleWithContent;

    HedgingConfigBuilder(HedgingRule hedgingRule, long initialHedgingDelayMillis) {
        this.hedgingRule = requireNonNull(hedgingRule, "hedgingRule");
        hedgingRuleWithContent = null;
        maxContentLength = 0;
        checkArgument(
                initialHedgingDelayMillis >= 0,
                "initialHedgingDelayMillis: %s (expected: >= 0)",
                initialHedgingDelayMillis);
        this.initialHedgingDelayMillis = initialHedgingDelayMillis;
    }

    HedgingConfigBuilder(HedgingRuleWithContent<T> hedgingRuleWithContent, long initialHedgingDelayMillis) {
        hedgingRule = null;
        this.hedgingRuleWithContent = requireNonNull(hedgingRuleWithContent, "hedgingRuleWithContent");
        maxContentLength = Integer.MAX_VALUE;
        checkArgument(
                initialHedgingDelayMillis >= 0,
                "initialHedgingDelayMillis: %s (expected: >= 0)",
                initialHedgingDelayMillis);
    }

    public HedgingConfigBuilder<T> maxContentLength(int maxContentLength) {
        requireNonNull(hedgingRuleWithContent, "hedgingRuleWithContent");
        checkArgument(maxContentLength > 0,
                      "maxContentLength: %s (expected: > 0)", maxContentLength);
        this.maxContentLength = maxContentLength;
        return this;
    }


    public HedgingConfigBuilder<T> initialHedgingDelayMillis(long initialHedgingDelayMillis) {
        checkArgument(
                initialHedgingDelayMillis >= 0,
                "initialHedgingDelayMillis: %s (expected: >= 0)",
                initialHedgingDelayMillis);
        this.initialHedgingDelayMillis = initialHedgingDelayMillis;
        return this;
    }

    public HedgingConfigBuilder<T> maxTotalAttempts(int maxTotalAttempts) {
        checkArgument(
                maxTotalAttempts > 0,
                "maxTotalAttempts: %s (expected: > 0)",
                maxTotalAttempts);
        this.maxTotalAttempts = maxTotalAttempts;
        return this;
    }

    public HedgingConfigBuilder<T> responseTimeoutMillisForEachAttempt(long responseTimeoutMillisForEachAttempt) {
        checkArgument(
                responseTimeoutMillisForEachAttempt >= 0,
                "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                responseTimeoutMillisForEachAttempt);
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        return this;
    }


    public HedgingConfigBuilder<T> responseTimeoutForEachAttempt(Duration responseTimeoutMillisForEachAttempt) {
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

    public HedgingConfig<T> build() {
        checkState(initialHedgingDelayMillis >= 0,
                   "initialHedgingDelayMillis: %s (expected: >= 0)", initialHedgingDelayMillis);

        if (hedgingRule != null) {
            return new HedgingConfig<>(hedgingRule, maxTotalAttempts, responseTimeoutMillisForEachAttempt, initialHedgingDelayMillis);
        }
        assert hedgingRuleWithContent != null;
        return new HedgingConfig<>(
                hedgingRuleWithContent,
                maxContentLength,
                maxTotalAttempts,
                responseTimeoutMillisForEachAttempt, initialHedgingDelayMillis);
    }

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    ToStringHelper toStringHelper() {
        return MoreObjects
                .toStringHelper(this)
                .omitNullValues()
                .add("hedgingRule", hedgingRule)
                .add("hedgingRuleWithContent", hedgingRuleWithContent)
                .add("maxTotalAttempts", maxTotalAttempts)
                .add("responseTimeoutMillisForEachAttempt", responseTimeoutMillisForEachAttempt)
                .add("maxContentLength", maxContentLength)
                .add("initialHedgingDelayMillis", initialHedgingDelayMillis);
    }
}