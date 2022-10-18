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

package com.linecorp.armeria.client.circuitbreaker;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.circuitbreaker.CircuitBreakerConverterUtil.fromCircuitBreakerRuleWithContent;

import java.util.concurrent.CompletionStage;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

final class HttpCircuitBreakerClientHandlerWrapper<CB, I extends Request> {

    private final CircuitBreakerClientHandler<CB, I> handler;
    @Nullable
    private final CircuitBreakerRule rule;
    @Nullable
    private final CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent;
    @Nullable
    private final CircuitBreakerRule fromRuleWithContent;
    private final boolean needsContentInRule;
    private final int maxContentLength;

    HttpCircuitBreakerClientHandlerWrapper(
            CircuitBreakerClientHandler<CB, I> handler,
            @Nullable CircuitBreakerRule rule,
            @Nullable CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent,
            boolean needsContentInRule,
            int maxContentLength) {
        this.handler = handler;
        this.rule = rule;
        this.ruleWithContent = ruleWithContent;
        this.maxContentLength = maxContentLength;
        this.needsContentInRule = needsContentInRule;
        if (ruleWithContent != null) {
            fromRuleWithContent = fromCircuitBreakerRuleWithContent(ruleWithContent);
        } else {
            fromRuleWithContent = null;
        }
    }

    HttpResponse report(ClientRequestContext ctx, HttpResponse response) {
        final CircuitBreakerRule rule = needsContentInRule ? fromRuleWithContent() : rule();
        final RequestLogProperty property =
                rule.requiresResponseTrailers() ? RequestLogProperty.RESPONSE_TRAILERS
                                                : RequestLogProperty.RESPONSE_HEADERS;

        if (!needsContentInRule) {
            reportResult(ctx, property);
            return response;
        } else {
            return reportResultWithContent(ctx, response, property);
        }
    }

    private void reportResult(ClientRequestContext ctx, RequestLogProperty logProperty) {
        ctx.log().whenAvailable(logProperty).thenAccept(log -> {
            final Throwable resCause =
                    log.isAvailable(RequestLogProperty.RESPONSE_CAUSE) ? log.responseCause() : null;
            handler.reportSuccessOrFailure(ctx, rule().shouldReportAsSuccess(ctx, resCause), resCause);
        });
    }

    private HttpResponse reportResultWithContent(ClientRequestContext ctx, HttpResponse response,
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
                handler.reportSuccessOrFailure(ctx, f, null);
            } catch (Throwable cause) {
                duplicator.abort(cause);
            }
        });

        return duplicate;
    }

    void reportSuccessOrFailure(ClientRequestContext ctx, Throwable cause) {
        final CircuitBreakerRule rule = needsContentInRule ? fromRuleWithContent() : rule();
        handler.reportSuccessOrFailure(ctx, rule.shouldReportAsSuccess(ctx, cause), cause);
    }

    private CircuitBreakerClientHandler<CB, I> handler() {
        return handler;
    }

    private CircuitBreakerRule rule() {
        checkState(rule != null, "rule is not set.");
        return rule;
    }

    private CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent() {
        checkState(ruleWithContent != null, "ruleWithContent is not set.");
        return ruleWithContent;
    }

    private CircuitBreakerRule fromRuleWithContent() {
        checkState(fromRuleWithContent != null, "ruleWithContent is not set.");
        return fromRuleWithContent;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("handler", handler)
                          .add("rule", rule)
                          .add("ruleWithContent", ruleWithContent)
                          .add("fromRuleWithContent", fromRuleWithContent)
                          .add("needsContentInRule", needsContentInRule)
                          .add("maxContentLength", maxContentLength)
                          .toString();
    }
}
