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

import static com.linecorp.armeria.client.retry.AbstractRetryRuleBuilder.DEFAULT_DECISION;
import static com.linecorp.armeria.client.retry.AbstractRetryRuleBuilder.NEXT_DECISION;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLogProperty;

final class RetryRuleUtil {

    static RetryStrategy toRetryStrategy(RetryRule rule) {
        return (ctx, cause) -> rule.shouldRetry(ctx, cause).thenApply(RetryDecision::backoff);
    }

    static RetryRule fromRetryStrategy(RetryStrategy strategy) {
        return (ctx, cause) -> strategy.shouldRetry(ctx, cause).thenApply(backoff -> {
            if (backoff == null) {
                return RetryDecision.noRetry();
            } else {
                return RetryDecision.retry(backoff);
            }
        });
    }

    static <T extends Response> RetryStrategyWithContent<T> toRetryStrategyWithContent(
            RetryRuleWithContent<T> rule) {
        return (ctx, content) -> rule.shouldRetry(ctx, content).thenApply(RetryDecision::backoff);
    }

    static <T extends Response> RetryRuleWithContent<T> fromRetryStrategyWithContent(
            RetryStrategyWithContent<T> strategy) {
        return (ctx, content) -> strategy.shouldRetry(ctx, content).thenApply(backoff -> {
            if (backoff == null) {
                return RetryDecision.noRetry();
            } else {
                return RetryDecision.retry(backoff);
            }
        });
    }

    static <T extends Response> RetryRule fromRetryWithContent(RetryRuleWithContent<T> retryRule) {
        return (ctx, content) -> retryRule.shouldRetry(ctx, null);
    }

    static <T extends Response> RetryRuleWithContent<T> fromRetryRule(RetryRule retryRule) {
        return (ctx, content) -> {
            final Throwable responseCause;
            if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
                responseCause = ctx.log().partial().responseCause();
            } else {
                responseCause = null;
            }
            return retryRule.shouldRetry(ctx, responseCause);
        };
    }

    static RetryRule orElse(RetryRule first, RetryRule second) {
        return (ctx, cause) -> orElse0(ctx, cause, first::shouldRetry, second::shouldRetry);
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> RetryRuleWithContent<T> orElse(RetryRule first,
                                                               RetryRuleWithContent<T> second) {
        return (ctx, response) -> {
            if (response instanceof HttpResponse) {
                try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                    final RetryRuleWithContent<T> duplicatedSecond =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) second, duplicator);
                    return orElse(ctx, response, fromRetryRule(first), duplicatedSecond);
                }
            } else {
                return orElse(ctx, response, fromRetryRule(first), second);
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> RetryRuleWithContent<T> orElse(RetryRuleWithContent<T> first,
                                                               RetryRule second) {
        return (ctx, response) -> {
            if (response instanceof HttpResponse) {
                try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                    final RetryRuleWithContent<T> duplicatedFirst =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) first, duplicator);
                    return orElse(ctx, response, duplicatedFirst, fromRetryRule(second));
                }
            } else {
                return orElse(ctx, response, first, fromRetryRule(second));
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> RetryRuleWithContent<T> orElse(RetryRuleWithContent<T> first,
                                                               RetryRuleWithContent<T> second) {
        return (ctx, response) -> {
            if (response instanceof HttpResponse) {
                try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                    final RetryRuleWithContent<T> duplicatedFirst =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) first, duplicator);
                    final RetryRuleWithContent<T> duplicatedSecond =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) second, duplicator);
                    return orElse(ctx, response, duplicatedFirst, duplicatedSecond);
                }
            } else {
                return orElse(ctx, response, first, second);
            }
        };
    }

    private static <T extends Response> CompletionStage<RetryDecision> orElse(
            ClientRequestContext ctx, T response,
            RetryRuleWithContent<T> first, RetryRuleWithContent<T> second) {
        return orElse0(ctx, response, first::shouldRetry, second::shouldRetry);
    }

    private static <T> CompletionStage<RetryDecision> orElse0(
            ClientRequestContext ctx, T causeOrResponse,
            BiFunction<? super ClientRequestContext, ? super T,
                    ? extends CompletionStage<RetryDecision>> first,
            BiFunction<? super ClientRequestContext, ? super T,
                    ? extends CompletionStage<RetryDecision>> second) {
        final CompletionStage<RetryDecision> decisionFuture = first.apply(ctx, causeOrResponse);
        if (decisionFuture == DEFAULT_DECISION) {
            return decisionFuture;
        }
        if (decisionFuture == NEXT_DECISION) {
            return second.apply(ctx, causeOrResponse);
        }
        return decisionFuture.thenCompose(decision -> {
            if (decision != RetryDecision.next()) {
                return decisionFuture;
            } else {
                return second.apply(ctx, causeOrResponse);
            }
        });
    }

    private static RetryRuleWithContent<HttpResponse>
    withDuplicator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, HttpResponseDuplicator duplicator) {
        return (ctx, unused) -> retryRuleWithContent.shouldRetry(ctx, duplicator.duplicate());
    }

    private RetryRuleUtil() {}
}
