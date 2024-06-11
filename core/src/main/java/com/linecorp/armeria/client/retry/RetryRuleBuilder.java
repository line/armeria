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

import static com.linecorp.armeria.client.retry.RetryRuleUtil.DEFAULT_DECISION;
import static com.linecorp.armeria.client.retry.RetryRuleUtil.NEXT_DECISION;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.AbstractRuleBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.AbstractRuleBuilderUtil;

/**
 * A builder for creating a new {@link RetryRule}.
 */
public final class RetryRuleBuilder extends AbstractRuleBuilder<RetryRuleBuilder> {

    RetryRuleBuilder(BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Sets the {@linkplain Backoff#ofDefault() default backoff} and returns a newly created {@link RetryRule}.
     */
    public RetryRule thenBackoff() {
        return thenBackoff(Backoff.ofDefault());
    }

    /**
     * Sets the specified {@link Backoff} and returns a newly created {@link RetryRule}.
     */
    public RetryRule thenBackoff(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return build(RetryDecision.retry(backoff));
    }

    /**
     * Returns a newly created {@link RetryRule} that never retries.
     */
    public RetryRule thenNoRetry() {
        return build(RetryDecision.noRetry());
    }

    private RetryRule build(RetryDecision decision) {
        if (decision != RetryDecision.noRetry() &&
            exceptionFilter() == null && responseHeadersFilter() == null &&
            responseTrailersFilter() == null && grpcTrailersFilter() == null &&
            totalDurationFilter() == null) {
            throw new IllegalStateException("Should set at least one retry rule if a backoff was set.");
        }
        final BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> ruleFilter =
                AbstractRuleBuilderUtil.buildFilter(requestHeadersFilter(), responseHeadersFilter(),
                                                    responseTrailersFilter(), grpcTrailersFilter(),
                                                    exceptionFilter(), totalDurationFilter(), false);
        return build(ruleFilter, decision, requiresResponseTrailers());
    }

    static RetryRule build(BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> ruleFilter,
                           RetryDecision decision, boolean requiresResponseTrailers) {
        final CompletableFuture<RetryDecision> decisionFuture;
        if (decision == RetryDecision.DEFAULT) {
            decisionFuture = DEFAULT_DECISION;
        } else {
            decisionFuture = UnmodifiableFuture.completedFuture(decision);
        }

        return new RetryRule() {
            @Override
            public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx,
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
