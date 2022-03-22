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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

final class RetryRuleUtil {

    static final CompletableFuture<RetryDecision> NEXT_DECISION =
            UnmodifiableFuture.completedFuture(RetryDecision.next());
    static final CompletableFuture<RetryDecision> DEFAULT_DECISION =
            UnmodifiableFuture.completedFuture(RetryDecision.retry(Backoff.ofDefault()));

    static <T extends Response> RetryRule fromRetryRuleWithContent(RetryRuleWithContent<T> retryRule) {
        return new RetryRule() {
            @Override
            public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx,
                                                              @Nullable Throwable cause) {
                return retryRule.shouldRetry(ctx, null, cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return retryRule.requiresResponseTrailers();
            }
        };
    }

    static <T extends Response> RetryRuleWithContent<T> fromRetryRule(RetryRule retryRule) {
        return new RetryRuleWithContent<T>() {
            @Override
            public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx, @Nullable T response,
                                                              @Nullable Throwable cause) {
                return retryRule.shouldRetry(ctx, cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return retryRule.requiresResponseTrailers();
            }
        };
    }

    static RetryRule orElse(RetryRule first, RetryRule second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new RetryRule() {
            @Override
            public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx,
                                                              @Nullable Throwable cause) {
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
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> RetryRuleWithContent<T> orElse(RetryRule first,
                                                               RetryRuleWithContent<T> second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new RetryRuleWithContent<T>() {
            @Override
            public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx, @Nullable T response,
                                                              @Nullable Throwable cause) {
                if (response instanceof HttpResponse) {
                    final HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator();
                    final RetryRuleWithContent<T> duplicatedSecond =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) second, duplicator);
                    final CompletionStage<RetryDecision> decision =
                            handle(ctx, response, cause, fromRetryRule(first), duplicatedSecond);
                    decision.handle((unused1, unused2) -> {
                        duplicator.abort();
                        return null;
                    });
                    return decision;
                } else {
                    return handle(ctx, response, cause, fromRetryRule(first), second);
                }
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> RetryRuleWithContent<T> orElse(RetryRuleWithContent<T> first,
                                                               RetryRule second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new RetryRuleWithContent<T>() {
            @Override
            public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx, @Nullable T response,
                                                              @Nullable Throwable cause) {
                if (response instanceof HttpResponse) {
                    try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                        final RetryRuleWithContent<T> duplicatedFirst =
                                (RetryRuleWithContent<T>) withDuplicator(
                                        (RetryRuleWithContent<HttpResponse>) first, duplicator);
                        return handle(ctx, response, cause, duplicatedFirst, fromRetryRule(second));
                    }
                } else {
                    return handle(ctx, response, cause, first, fromRetryRule(second));
                }
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> RetryRuleWithContent<T> orElse(RetryRuleWithContent<T> first,
                                                               RetryRuleWithContent<T> second) {
        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new RetryRuleWithContent<T>() {
            @Override
            public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx, @Nullable T response,
                                                              @Nullable Throwable cause) {
                if (response instanceof HttpResponse) {
                    final HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator();
                    final RetryRuleWithContent<T> duplicatedFirst =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) first, duplicator);
                    final RetryRuleWithContent<T> duplicatedSecond =
                            (RetryRuleWithContent<T>) withDuplicator(
                                    (RetryRuleWithContent<HttpResponse>) second, duplicator);
                    final CompletionStage<RetryDecision> decision =
                            handle(ctx, response, cause, duplicatedFirst, duplicatedSecond);
                    decision.handle((unused1, unused2) -> {
                        duplicator.abort();
                        return null;
                    });
                    return decision;
                } else {
                    return handle(ctx, response, cause, first, second);
                }
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    private static <T extends Response> CompletionStage<RetryDecision> handle(
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
    withDuplicator(RetryRuleWithContent<HttpResponse> ruleWithContent, HttpResponseDuplicator duplicator) {
        return new RetryRuleWithContent<HttpResponse>() {
            @Override
            public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx,
                                                              @Nullable HttpResponse response,
                                                              @Nullable Throwable cause) {
                return ruleWithContent.shouldRetry(ctx, duplicator.duplicate(), cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return ruleWithContent.requiresResponseTrailers();
            }
        };
    }

    private RetryRuleUtil() {}
}
