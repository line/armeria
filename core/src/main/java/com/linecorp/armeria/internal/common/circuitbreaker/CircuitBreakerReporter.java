/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.common.circuitbreaker;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.circuitbreaker.CircuitBreakerConverterUtil.fromCircuitBreakerRuleWithContent;

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientCallbacks;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerDecision;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

public final class CircuitBreakerReporter<CB> {

    @Nullable
    private final CircuitBreakerRule rule;
    @Nullable
    private final CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent;
    @Nullable
    private final CircuitBreakerRule fromRuleWithContent;
    private final boolean needsContentInRule;
    private final int maxContentLength;
    private final CircuitBreakerClientCallbacks<CB> callbacks;

    public CircuitBreakerReporter(@Nullable CircuitBreakerRule rule,
                                  @Nullable CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent,
                                  boolean needsContentInRule,
                                  int maxContentLength,
                                  CircuitBreakerClientCallbacks<CB> callbacks) {
        this.rule = rule;
        this.ruleWithContent = ruleWithContent;
        this.maxContentLength = maxContentLength;
        this.needsContentInRule = needsContentInRule;
        this.callbacks = callbacks;
        if (ruleWithContent != null) {
            fromRuleWithContent = fromCircuitBreakerRuleWithContent(ruleWithContent);
        } else {
            fromRuleWithContent = null;
        }
    }

    /**
     * Returns the {@link CircuitBreakerRule}.
     *
     * @throws IllegalStateException if the {@link CircuitBreakerRule} is not set
     */
    private CircuitBreakerRule rule() {
        checkState(rule != null, "rule is not set.");
        return rule;
    }

    /**
     * Returns the {@link CircuitBreakerRuleWithContent}.
     *
     * @throws IllegalStateException if the {@link CircuitBreakerRuleWithContent} is not set
     */
    private CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent() {
        checkState(ruleWithContent != null, "ruleWithContent is not set.");
        return ruleWithContent;
    }

    /**
     * Returns the {@link CircuitBreakerRule} derived from {@link #ruleWithContent()}.
     *
     * @throws IllegalStateException if the {@link CircuitBreakerRuleWithContent} is not set
     */
    private CircuitBreakerRule fromRuleWithContent() {
        checkState(fromRuleWithContent != null, "ruleWithContent is not set.");
        return fromRuleWithContent;
    }

    public HttpResponse report(ClientRequestContext ctx, CB circuitBreaker, HttpResponse response) {
        final CircuitBreakerRule rule = needsContentInRule ? fromRuleWithContent() : rule();
        final RequestLogProperty property =
                rule.requiresResponseTrailers() ? RequestLogProperty.RESPONSE_TRAILERS
                                                : RequestLogProperty.RESPONSE_HEADERS;

        if (!needsContentInRule) {
            reportResult(ctx, circuitBreaker, property);
            return response;
        } else {
            return reportResultWithContent(ctx, response, circuitBreaker, property);
        }
    }

    private void reportResult(ClientRequestContext ctx, CB circuitBreaker,
                              RequestLogProperty logProperty) {
        ctx.log().whenAvailable(logProperty).thenAccept(log -> {
            final Throwable resCause =
                    log.isAvailable(RequestLogProperty.RESPONSE_CAUSE) ? log.responseCause() : null;
            reportSuccessOrFailure(circuitBreaker, ctx,
                                   rule().shouldReportAsSuccess(ctx, resCause));
        });
    }

    private HttpResponse reportResultWithContent(ClientRequestContext ctx, HttpResponse response,
                                                 CB circuitBreaker,
                                                 RequestLogProperty logProperty) {

        final HttpResponseDuplicator duplicator = response.toDuplicator(ctx.eventLoop().withoutContext(),
                                                                        ctx.maxResponseLength());
        final TruncatingHttpResponse truncatingHttpResponse =
                new TruncatingHttpResponse(duplicator.duplicate(), maxContentLength);
        final HttpResponse duplicate = duplicator.duplicate();
        duplicator.close();

        ctx.log().whenAvailable(logProperty).thenAccept(log -> {
            try {
                final CompletionStage<CircuitBreakerDecision> f =
                        ruleWithContent().shouldReportAsSuccess(ctx, truncatingHttpResponse, null);
                f.handle((unused1, unused2) -> {
                    truncatingHttpResponse.abort();
                    return null;
                });
                reportSuccessOrFailure(circuitBreaker, ctx, f);
            } catch (Throwable cause) {
                duplicator.abort(cause);
            }
        });

        return duplicate;
    }

    public void reportSuccessOrFailure(CB circuitBreaker,
                                       ClientRequestContext ctx,
                                       CompletionStage<CircuitBreakerDecision> future) {
        reportSuccessOrFailure(circuitBreaker, ctx, future, callbacks);
    }

    public static <CB> void reportSuccessOrFailure(CB circuitBreaker,
                                                   ClientRequestContext ctx,
                                                   CompletionStage<CircuitBreakerDecision> future,
                                                   CircuitBreakerClientCallbacks<CB> callbacks) {
        future.handle((decision, unused) -> {
            if (decision != null) {
                if (decision == CircuitBreakerDecision.success() || decision == CircuitBreakerDecision.next()) {
                    callbacks.onSuccess(circuitBreaker, ctx);
                } else if (decision == CircuitBreakerDecision.failure()) {
                    callbacks.onFailure(circuitBreaker, ctx);
                } else {
                    // Ignore, does not count as a success nor failure.
                }
            }
            return null;
        }).exceptionally(CompletionActions::log);
    }

    public void reportSuccessOrFailure(CB circuitBreaker, ClientRequestContext ctx, Throwable cause) {
        final CircuitBreakerRule rule = needsContentInRule ? fromRuleWithContent() : rule();
        reportSuccessOrFailure(circuitBreaker, ctx, rule.shouldReportAsSuccess(ctx, cause));
    }
}
