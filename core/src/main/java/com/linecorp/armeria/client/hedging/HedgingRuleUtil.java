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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

final class HedgingRuleUtil {
    static final CompletableFuture<HedgingDecision> NEXT_DECISION =
            UnmodifiableFuture.completedFuture(HedgingDecision.next());

    static HedgingRule orElse(HedgingRule first, HedgingRule second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new HedgingRule() {
            @Override
            public CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx,
                                                              @Nullable Throwable cause) {
                final CompletionStage<HedgingDecision> decisionFuture = first.shouldHedge(ctx, cause);
                if (decisionFuture == NEXT_DECISION) {
                    return second.shouldHedge(ctx, cause);
                }
                return decisionFuture.thenCompose(decision -> {
                    if (decision != HedgingDecision.next()) {
                        return decisionFuture;
                    } else {
                        return second.shouldHedge(ctx, cause);
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
    static <T extends Response> HedgingRuleWithContent<T> orElse(HedgingRule first,
                                                               HedgingRuleWithContent<T> second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new HedgingRuleWithContent<T>() {
            @Override
            public CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx, @Nullable T response,
                                                              @Nullable Throwable cause) {
                if (response instanceof HttpResponse) {
                    final HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator();
                    final HedgingRuleWithContent<T> duplicatedSecond =
                            (HedgingRuleWithContent<T>) withDuplicator(
                                    (HedgingRuleWithContent<HttpResponse>) second, duplicator);
                    final CompletionStage<HedgingDecision> decision =
                            handle(ctx, response, cause, fromHedgingRule(first), duplicatedSecond);
                    decision.handle((unused1, unused2) -> {
                        duplicator.abort();
                        return null;
                    });
                    return decision;
                } else {
                    return handle(ctx, response, cause, fromHedgingRule(first), second);
                }
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> HedgingRuleWithContent<T> orElse(HedgingRuleWithContent<T> first,
                                                               HedgingRule second) {

        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new HedgingRuleWithContent<T>() {
            @Override
            public CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx, @Nullable T response,
                                                              @Nullable Throwable cause) {
                if (response instanceof HttpResponse) {
                    try (HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator()) {
                        final HedgingRuleWithContent<T> duplicatedFirst =
                                (HedgingRuleWithContent<T>) withDuplicator((HedgingRuleWithContent<HttpResponse>) first, duplicator);
                        return handle(ctx, response, cause, duplicatedFirst, fromHedgingRule(second));
                    }
                } else {
                    return handle(ctx, response, cause, first, fromHedgingRule(second));
                }
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Response> HedgingRuleWithContent<T> orElse(HedgingRuleWithContent<T> first,
                                                               HedgingRuleWithContent<T> second) {
        final boolean requiresResponseTrailers = first.requiresResponseTrailers() ||
                                                 second.requiresResponseTrailers();

        return new HedgingRuleWithContent<T>() {
            @Override
            public CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx, @Nullable T response,
                                                              @Nullable Throwable cause) {
                if (response instanceof HttpResponse) {
                    final HttpResponseDuplicator duplicator = ((HttpResponse) response).toDuplicator();
                    final HedgingRuleWithContent<T> duplicatedFirst =
                            (HedgingRuleWithContent<T>) withDuplicator(
                                    (HedgingRuleWithContent<HttpResponse>) first, duplicator);
                    final HedgingRuleWithContent<T> duplicatedSecond =
                            (HedgingRuleWithContent<T>) withDuplicator(
                                    (HedgingRuleWithContent<HttpResponse>) second, duplicator);
                    final CompletionStage<HedgingDecision> decision =
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

    private static <T extends Response> CompletionStage<HedgingDecision> handle(
            ClientRequestContext ctx, @Nullable T response, @Nullable Throwable cause,
            HedgingRuleWithContent<T> first, HedgingRuleWithContent<T> second) {
        final CompletionStage<HedgingDecision> decisionFuture = first.shouldHedge(ctx, response, cause);


        if (decisionFuture == NEXT_DECISION) {
            return second.shouldHedge(ctx, response, cause);
        }
        return decisionFuture.thenCompose(decision -> {
            if (decision != HedgingDecision.next()) {
                return decisionFuture;
            } else {
                return second.shouldHedge(ctx, response, cause);
            }
        });
    }

    private static HedgingRuleWithContent<HttpResponse>
    withDuplicator(HedgingRuleWithContent<HttpResponse> ruleWithContent, HttpResponseDuplicator duplicator) {
        return new HedgingRuleWithContent<HttpResponse>() {
            @Override
            public CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx,
                                                              @Nullable HttpResponse response,
                                                              @Nullable Throwable cause) {
                return ruleWithContent.shouldHedge(ctx, duplicator.duplicate(), cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return ruleWithContent.requiresResponseTrailers();
            }
        };
    }

    static <T extends Response> HedgingRuleWithContent<T> fromHedgingRule(HedgingRule hedgingRule) {
        return new HedgingRuleWithContent<T>() {
            @Override
            public CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx, @Nullable T response,
                                                              @Nullable Throwable cause) {
                return hedgingRule.shouldHedge(ctx, cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return hedgingRule.requiresResponseTrailers();
            }
        };
    }

    static <T extends Response> HedgingRule fromHedgingRuleWithContent(HedgingRuleWithContent<T> hedgingRule) {
        return new HedgingRule() {
            @Override
            public CompletionStage<HedgingDecision> shouldHedge(ClientRequestContext ctx,
                                                              @Nullable Throwable cause) {
                return hedgingRule.shouldHedge(ctx, null, cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return hedgingRule.requiresResponseTrailers();
            }
        };
    }

    private HedgingRuleUtil() {}
}
