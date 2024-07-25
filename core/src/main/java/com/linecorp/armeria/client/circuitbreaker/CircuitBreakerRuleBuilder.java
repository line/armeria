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

package com.linecorp.armeria.client.circuitbreaker;

import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.FAILURE_DECISION;
import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.IGNORE_DECISION;
import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.NEXT_DECISION;
import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.SUCCESS_DECISION;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.AbstractRuleBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.AbstractRuleBuilderUtil;

/**
 * A builder for creating a new {@link CircuitBreakerRule}.
 */
public final class CircuitBreakerRuleBuilder extends AbstractRuleBuilder<CircuitBreakerRuleBuilder> {

    CircuitBreakerRuleBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that determines a {@link Response} as a success when
     * the rule matches.
     */
    public CircuitBreakerRule thenSuccess() {
        return build(CircuitBreakerDecision.success());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that determines a {@link Response} as a failure when
     * the rule matches.
     */
    public CircuitBreakerRule thenFailure() {
        return build(CircuitBreakerDecision.failure());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that ignores a {@link Response} when the rule matches.
     */
    public CircuitBreakerRule thenIgnore() {
        return build(CircuitBreakerDecision.ignore());
    }

    private CircuitBreakerRule build(CircuitBreakerDecision decision) {
        final BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> ruleFilter =
                AbstractRuleBuilderUtil.buildFilter(requestHeadersFilter(), responseHeadersFilter(),
                                                    responseTrailersFilter(), grpcTrailersFilter(),
                                                    exceptionFilter(), totalDurationFilter(), false);
        return build(ruleFilter, decision, requiresResponseTrailers());
    }

    static CircuitBreakerRule build(
            BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> ruleFilter,
            CircuitBreakerDecision decision, boolean requiresResponseTrailers) {
        final CompletableFuture<CircuitBreakerDecision> decisionFuture;
        if (decision == CircuitBreakerDecision.success()) {
            decisionFuture = SUCCESS_DECISION;
        } else if (decision == CircuitBreakerDecision.failure()) {
            decisionFuture = FAILURE_DECISION;
        } else if (decision == CircuitBreakerDecision.ignore()) {
            decisionFuture = IGNORE_DECISION;
        } else {
            decisionFuture = NEXT_DECISION;
        }

        return new CircuitBreakerRule() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
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
