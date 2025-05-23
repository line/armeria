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

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.AbstractRuleWithContentBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.internal.client.RuleFilter;


public final class HedgingRuleWithContentBuilder<T extends Response>
        extends AbstractRuleWithContentBuilder<HedgingRuleWithContentBuilder<T>, T> {

    HedgingRuleWithContentBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    public HedgingRuleWithContent<T> thenHedge(long hedgingDelayMillis) {
        checkArgument(hedgingDelayMillis >= 0,
                       "hedgingDelayMillis: %s (expected: >= 0)", hedgingDelayMillis);

        return build(HedgingDecision.hedge(hedgingDelayMillis));
    }

    public HedgingRuleWithContent<T> thenNoHedge() {
        return build(HedgingDecision.noHedge());
    }

    HedgingRuleWithContent<T> build(HedgingDecision decision) {
        final BiFunction<? super ClientRequestContext, ? super T,
                ? extends CompletionStage<Boolean>> responseFilter = responseFilter();
        final boolean hasResponseFilter = responseFilter != null;
        if (decision != HedgingDecision.noHedge() && exceptionFilter() == null &&
            responseHeadersFilter() == null && responseTrailersFilter() == null &&
            grpcTrailersFilter() == null && !hasResponseFilter) {
            throw new IllegalStateException("Should set at least one retry rule if a hedgingDelayMillis was set.");
        }

        final RuleFilter ruleFilter =
                RuleFilter.of(requestHeadersFilter(), responseHeadersFilter(),
                              responseTrailersFilter(), grpcTrailersFilter(),
                              exceptionFilter(), totalDurationFilter(),
                              hasResponseFilter);
        final HedgingRule first = HedgingRuleBuilder.build(
                ruleFilter, decision, requiresResponseTrailers());

        if (!hasResponseFilter) {
            return HedgingRuleUtil.fromHedgingRule(first);
        }

        final HedgingRuleWithContent<T> second = (ctx, content, cause) -> {
            if (content == null) {
                return NEXT_DECISION;
            }
            assert responseFilter != null;
            return responseFilter.apply(ctx, content)
                                 .handle((matched, cause0) -> {
                                     if (cause0 != null) {
                                         return HedgingDecision.next();
                                     }
                                     return matched ? decision : HedgingDecision.next();
                                 });
        };
        return HedgingRuleUtil.orElse(first, second);
    }
}
