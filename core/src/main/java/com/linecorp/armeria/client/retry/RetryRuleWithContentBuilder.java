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

import static com.linecorp.armeria.client.retry.RetryRuleUtil.NEXT_DECISION;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.AbstractRuleWithContentBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.internal.client.AbstractRuleBuilderUtil;

/**
 * A builder for creating a new {@link RetryRuleWithContent}.
 */
public final class RetryRuleWithContentBuilder<T extends Response>
        extends AbstractRuleWithContentBuilder<RetryRuleWithContentBuilder<T>, T> {

    RetryRuleWithContentBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Sets the {@linkplain Backoff#ofDefault() default backoff} and
     * returns a newly created {@link RetryRuleWithContent}.
     */
    public RetryRuleWithContent<T> thenBackoff() {
        return thenBackoff(Backoff.ofDefault());
    }

    /**
     * Sets the specified {@link Backoff} and returns a newly created {@link RetryRuleWithContent}.
     */
    public RetryRuleWithContent<T> thenBackoff(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return build(RetryDecision.retry(backoff));
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that never retries.
     */
    public RetryRuleWithContent<T> thenNoRetry() {
        return build(RetryDecision.noRetry());
    }

    RetryRuleWithContent<T> build(RetryDecision decision) {
        final BiFunction<? super ClientRequestContext, ? super T,
                ? extends CompletionStage<Boolean>> responseFilter = responseFilter();
        final boolean hasResponseFilter = responseFilter != null;
        if (decision != RetryDecision.noRetry() && exceptionFilter() == null &&
            responseHeadersFilter() == null && responseTrailersFilter() == null &&
            grpcTrailersFilter() == null && !hasResponseFilter) {
            throw new IllegalStateException("Should set at least one retry rule if a backoff was set.");
        }

        final BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> ruleFilter =
                AbstractRuleBuilderUtil.buildFilter(requestHeadersFilter(), responseHeadersFilter(),
                                                    responseTrailersFilter(), grpcTrailersFilter(),
                                                    exceptionFilter(), totalDurationFilter(),
                                                    hasResponseFilter);
        final RetryRule first = RetryRuleBuilder.build(
                ruleFilter, decision, requiresResponseTrailers());

        if (!hasResponseFilter) {
            return RetryRuleUtil.fromRetryRule(first);
        }

        final RetryRuleWithContent<T> second = (ctx, content, cause) -> {
            if (content == null) {
                return NEXT_DECISION;
            }
            return responseFilter.apply(ctx, content)
                                 .handle((matched, cause0) -> {
                                     if (cause0 != null) {
                                         return RetryDecision.next();
                                     }
                                     return matched ? decision : RetryDecision.next();
                                 });
        };
        return RetryRuleUtil.orElse(first, second);
    }
}
