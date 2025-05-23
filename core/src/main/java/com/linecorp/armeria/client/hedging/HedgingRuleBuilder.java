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
import static com.linecorp.armeria.client.hedging.HedgingRuleUtil.NEXT_DECISION;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.AbstractRuleBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.RuleFilter;


public final class HedgingRuleBuilder extends AbstractRuleBuilder<HedgingRuleBuilder> {
    HedgingRuleBuilder(BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    public HedgingRule thenHedge(long hedgingDelayMillis) {
        checkArgument(hedgingDelayMillis >= 0,
                         "hedgingDelayMillis: %s (expected: >= 0)", hedgingDelayMillis);
        return build(HedgingDecision.hedge(hedgingDelayMillis));
    }

    public HedgingRule thenNoHedge() {
        return build(HedgingDecision.noHedge());
    }

    private HedgingRule build(HedgingDecision decision) {
        final boolean shouldHedgingContinue = decision.hedgingDelayMillis() >= 0;
        assert shouldHedgingContinue || decision == HedgingDecision.noHedge();

        if (shouldHedgingContinue &&
            exceptionFilter() == null && responseHeadersFilter() == null &&
            responseTrailersFilter() == null && grpcTrailersFilter() == null &&
            totalDurationFilter() == null) {
            throw new IllegalStateException("Should set at least one hedging rule if hedging should continue.");
        }
        final RuleFilter ruleFilter =
                RuleFilter.of(requestHeadersFilter(), responseHeadersFilter(),
                              responseTrailersFilter(), grpcTrailersFilter(),
                              exceptionFilter(), totalDurationFilter(), false);

        return build(ruleFilter, decision, requiresResponseTrailers());
    }

    static HedgingRule build(BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> ruleFilter,
                           HedgingDecision decision, boolean requiresResponseTrailers) {
        assert decision != HedgingDecision.next();

        final CompletableFuture<HedgingDecision> decisionFuture = UnmodifiableFuture.completedFuture(decision);

        return new HedgingRule() {
            @Override
            public CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx,
                                                              @Nullable Throwable cause) {
                return ruleFilter.apply(ctx, cause) ? decisionFuture : NEXT_DECISION;
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }
}
