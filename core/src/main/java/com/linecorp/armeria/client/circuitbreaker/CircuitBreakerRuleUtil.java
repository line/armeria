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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

final class CircuitBreakerRuleUtil {

    static final CompletableFuture<CircuitBreakerDecision> SUCCESS_DECISION =
            UnmodifiableFuture.completedFuture(CircuitBreakerDecision.success());
    static final CompletableFuture<CircuitBreakerDecision> FAILURE_DECISION =
            UnmodifiableFuture.completedFuture(CircuitBreakerDecision.failure());
    static final CompletableFuture<CircuitBreakerDecision> IGNORE_DECISION =
            UnmodifiableFuture.completedFuture(CircuitBreakerDecision.ignore());
    static final CompletableFuture<CircuitBreakerDecision> NEXT_DECISION =
            UnmodifiableFuture.completedFuture(CircuitBreakerDecision.next());

    static <T extends Response> CircuitBreakerRuleWithContent<T> fromCircuitBreakerRule(
            CircuitBreakerRule circuitBreakerRule) {
        return new CircuitBreakerRuleWithContent<T>() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                                 @Nullable T response,
                                                                                 @Nullable Throwable cause) {
                return circuitBreakerRule.shouldReportAsSuccess(ctx, cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return circuitBreakerRule.requiresResponseTrailers();
            }
        };
    }

    static <T extends Response> CircuitBreakerRule fromCircuitBreakerRuleWithContent(
            CircuitBreakerRuleWithContent<T> circuitBreakerRuleWithContent) {
        return new CircuitBreakerRule() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                                 @Nullable Throwable cause) {
                return circuitBreakerRuleWithContent.shouldReportAsSuccess(ctx, null, cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return circuitBreakerRuleWithContent.requiresResponseTrailers();
            }
        };
    }

    static CircuitBreakerRule orElse(CircuitBreakerRule first, CircuitBreakerRule second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new CircuitBreakerRule() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                                 @Nullable Throwable cause) {
                final CompletionStage<CircuitBreakerDecision> decisionFuture =
                        first.shouldReportAsSuccess(ctx, cause);
                if (decisionFuture == SUCCESS_DECISION ||
                    decisionFuture == FAILURE_DECISION ||
                    decisionFuture == IGNORE_DECISION) {
                    return decisionFuture;
                }
                if (decisionFuture == NEXT_DECISION) {
                    return second.shouldReportAsSuccess(ctx, cause);
                }
                return decisionFuture.thenCompose(decision -> {
                    if (decision != CircuitBreakerDecision.next()) {
                        return decisionFuture;
                    } else {
                        return second.shouldReportAsSuccess(ctx, cause);
                    }
                });
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    static <T extends Response> CircuitBreakerRuleWithContent<T> orElse(
            CircuitBreakerRule first, CircuitBreakerRuleWithContent<T> second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new CircuitBreakerRuleWithContent<T>() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                                 @Nullable T response,
                                                                                 @Nullable Throwable cause) {
                if (response instanceof HttpResponse) {
                    try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                        @SuppressWarnings("unchecked")
                        final CircuitBreakerRuleWithContent<T> duplicatedSecond =
                                (CircuitBreakerRuleWithContent<T>) withDuplicator(
                                        (CircuitBreakerRuleWithContent<HttpResponse>) second, duplicator);
                        return handle(ctx, response, cause, fromCircuitBreakerRule(first), duplicatedSecond);
                    }
                } else {
                    return handle(ctx, response, cause, fromCircuitBreakerRule(first), second);
                }
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    static <T extends Response> CircuitBreakerRuleWithContent<T> orElse(CircuitBreakerRuleWithContent<T> first,
                                                                        CircuitBreakerRule second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new CircuitBreakerRuleWithContent<T>() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                                 @Nullable T response,
                                                                                 @Nullable Throwable cause) {
                if (response instanceof HttpResponse) {
                    try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                        @SuppressWarnings("unchecked")
                        final CircuitBreakerRuleWithContent<T> duplicatedFirst =
                                (CircuitBreakerRuleWithContent<T>) withDuplicator(
                                        (CircuitBreakerRuleWithContent<HttpResponse>) first, duplicator);
                        return handle(ctx, response, cause, duplicatedFirst, fromCircuitBreakerRule(second));
                    }
                } else {
                    return handle(ctx, response, cause, first, fromCircuitBreakerRule(second));
                }
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> CircuitBreakerRuleWithContent<T> orElse(
            CircuitBreakerRuleWithContent<T> first, CircuitBreakerRuleWithContent<T> second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new CircuitBreakerRuleWithContent<T>() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                                 @Nullable T response,
                                                                                 @Nullable Throwable cause) {
                if (response instanceof HttpResponse) {
                    try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                        final CircuitBreakerRuleWithContent<T> duplicatedFirst =
                                (CircuitBreakerRuleWithContent<T>) withDuplicator(
                                        (CircuitBreakerRuleWithContent<HttpResponse>) first, duplicator);
                        final CircuitBreakerRuleWithContent<T> duplicatedSecond =
                                (CircuitBreakerRuleWithContent<T>) withDuplicator(
                                        (CircuitBreakerRuleWithContent<HttpResponse>) second, duplicator);
                        return handle(ctx, response, cause, duplicatedFirst, duplicatedSecond);
                    }
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

    private static <T extends Response> CompletionStage<CircuitBreakerDecision> handle(
            ClientRequestContext ctx, @Nullable T response, @Nullable Throwable cause,
            CircuitBreakerRuleWithContent<T> first, CircuitBreakerRuleWithContent<T> second) {

        final CompletionStage<CircuitBreakerDecision> decisionFuture =
                first.shouldReportAsSuccess(ctx, response, cause);
        if (decisionFuture == SUCCESS_DECISION ||
            decisionFuture == FAILURE_DECISION ||
            decisionFuture == IGNORE_DECISION) {
            return decisionFuture;
        }
        if (decisionFuture == NEXT_DECISION) {
            return second.shouldReportAsSuccess(ctx, response, cause);
        }
        return decisionFuture.thenCompose(decision -> {
            if (decision != CircuitBreakerDecision.next()) {
                return decisionFuture;
            } else {
                return second.shouldReportAsSuccess(ctx, response, cause);
            }
        });
    }

    private static CircuitBreakerRuleWithContent<HttpResponse>
    withDuplicator(CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent,
                   HttpResponseDuplicator duplicator) {

        return new CircuitBreakerRuleWithContent<HttpResponse>() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(
                    ClientRequestContext ctx, @Nullable HttpResponse response, @Nullable Throwable cause) {

                return ruleWithContent.shouldReportAsSuccess(ctx, duplicator.duplicate(), cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return ruleWithContent.requiresResponseTrailers();
            }
        };
    }

    private CircuitBreakerRuleUtil() {}
}
