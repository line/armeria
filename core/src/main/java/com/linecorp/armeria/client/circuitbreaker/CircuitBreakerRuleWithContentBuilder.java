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

import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.NEXT_DECISION;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.AbstractRuleWithContentBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.internal.client.AbstractRuleBuilderUtil;

/**
 * A builder for creating a new {@link CircuitBreakerRuleWithContent}.
 * @param <T> the response type
 */
public final class CircuitBreakerRuleWithContentBuilder<T extends Response>
        extends AbstractRuleWithContentBuilder<CircuitBreakerRuleWithContentBuilder<T>, T> {

    CircuitBreakerRuleWithContentBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContent} that determines a {@link Response} as
     * a success when the rule matches.
     */
    public CircuitBreakerRuleWithContent<T> thenSuccess() {
        return build(CircuitBreakerDecision.success());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContent} that determines a {@link Response} as
     * a failure when the rule matches.
     */
    public CircuitBreakerRuleWithContent<T> thenFailure() {
        return build(CircuitBreakerDecision.failure());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContent} that ignores a {@link Response} when
     * the rule matches.
     */
    public CircuitBreakerRuleWithContent<T> thenIgnore() {
        return build(CircuitBreakerDecision.ignore());
    }

    private CircuitBreakerRuleWithContent<T> build(CircuitBreakerDecision decision) {
        final BiFunction<? super ClientRequestContext, ? super T,
                ? extends CompletionStage<Boolean>> responseFilter = responseFilter();
        final boolean hasResponseFilter = responseFilter != null;
        final BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> ruleFilter =
                AbstractRuleBuilderUtil.buildFilter(requestHeadersFilter(), responseHeadersFilter(),
                                                    responseTrailersFilter(), grpcTrailersFilter(),
                                                    exceptionFilter(), totalDurationFilter(),
                                                    hasResponseFilter);
        final CircuitBreakerRule first = CircuitBreakerRuleBuilder.build(
                ruleFilter, decision, requiresResponseTrailers());
        if (!hasResponseFilter) {
            return CircuitBreakerRuleUtil.fromCircuitBreakerRule(first);
        }

        final CircuitBreakerRuleWithContent<T> second = (ctx, content, cause) -> {
            if (content == null) {
                return NEXT_DECISION;
            }
            return responseFilter.apply(ctx, content)
                                 .handle((matched, cause0) -> {
                                     if (cause0 != null) {
                                         return CircuitBreakerDecision.next();
                                     }
                                     return matched ? decision : CircuitBreakerDecision.next();
                                 });
        };
        return CircuitBreakerRuleUtil.orElse(first, second);
    }
}
