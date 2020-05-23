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

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Response;

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
        return (ctx, content) -> rule.shouldRetry(ctx, content, null).thenApply(RetryDecision::backoff);
    }

    static <T extends Response> RetryRuleWithContent<T> fromRetryStrategyWithContent(
            RetryStrategyWithContent<T> strategy) {
        return (ctx, content, cause) -> strategy.shouldRetry(ctx, content).thenApply(backoff -> {
            if (backoff == null) {
                return RetryDecision.noRetry();
            } else {
                return RetryDecision.retry(backoff);
            }
        });
    }

    static <T extends Response> RetryRule fromRetryRuleWithContent(RetryRuleWithContent<T> retryRule) {
        return (ctx, cause) -> retryRule.shouldRetry(ctx, null, cause);
    }

    static <T extends Response> RetryRuleWithContent<T> fromRetryRule(RetryRule retryRule) {
        return (ctx, content, cause) -> retryRule.shouldRetry(ctx, cause);
    }

    static RetryRule orElse(RetryRule first, RetryRule second) {
        return (ctx, cause) -> {
            final CompletionStage<RetryDecision> decisionFuture = first.shouldRetry(ctx, cause);
            if (decisionFuture == DEFAULT_DECISION) {
                return decisionFuture;
            }
            if (decisionFuture == NEXT_DECISION) {
                return second.shouldRetry(ctx, cause);
            }
            return decisionFuture.thenCompose(decision -> {
                if (decision != RetryDecision.next()) {
                    return decisionFuture;
                } else {
                    return second.shouldRetry(ctx, cause);
                }
            });
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> RetryRuleWithContent<T> orElse(RetryRule first,
                                                               RetryRuleWithContent<T> second) {
        return (ctx, response, cause) -> {
            if (response instanceof HttpResponse) {
                try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                    final RetryRuleWithContent<T> duplicatedSecond =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) second, duplicator);
                    return orElse(ctx, response, cause, fromRetryRule(first), duplicatedSecond);
                }
            } else {
                return orElse(ctx, response, cause, fromRetryRule(first), second);
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> RetryRuleWithContent<T> orElse(RetryRuleWithContent<T> first,
                                                               RetryRule second) {
        return (ctx, response, cause) -> {
            if (response instanceof HttpResponse) {
                try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                    final RetryRuleWithContent<T> duplicatedFirst =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) first, duplicator);
                    return orElse(ctx, response, cause, duplicatedFirst, fromRetryRule(second));
                }
            } else {
                return orElse(ctx, response, cause, first, fromRetryRule(second));
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> RetryRuleWithContent<T> orElse(RetryRuleWithContent<T> first,
                                                               RetryRuleWithContent<T> second) {
        return (ctx, response, cause) -> {
            if (response instanceof HttpResponse) {
                try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                    final RetryRuleWithContent<T> duplicatedFirst =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) first, duplicator);
                    final RetryRuleWithContent<T> duplicatedSecond =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) second, duplicator);
                    return orElse(ctx, response, cause, duplicatedFirst, duplicatedSecond);
                }
            } else {
                return orElse(ctx, response, cause, first, second);
            }
        };
    }

    private static <T extends Response> CompletionStage<RetryDecision> orElse(
            ClientRequestContext ctx, @Nullable T response, @Nullable Throwable cause,
            RetryRuleWithContent<T> first, RetryRuleWithContent<T> second) {
        final CompletionStage<RetryDecision> decisionFuture = first.shouldRetry(ctx, response, cause);
        if (decisionFuture == DEFAULT_DECISION) {
            return decisionFuture;
        }
        if (decisionFuture == NEXT_DECISION) {
            return second.shouldRetry(ctx, response, cause);
        }
        return decisionFuture.thenCompose(decision -> {
            if (decision != RetryDecision.next()) {
                return decisionFuture;
            } else {
                return second.shouldRetry(ctx, response, cause);
            }
        });
    }

    private static RetryRuleWithContent<HttpResponse>
    withDuplicator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, HttpResponseDuplicator duplicator) {
        return (ctx, unused, cause) -> retryRuleWithContent.shouldRetry(ctx, duplicator.duplicate(), cause);
    }

    private RetryRuleUtil() {}
}
