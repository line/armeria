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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A builder which creates a {@link RetryRule}.
 */
public final class RetryRuleBuilder extends AbstractRetryRuleBuilder {

    RetryRuleBuilder(Predicate<? super RequestHeaders> requestHeadersFilter) {
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

    RetryRule build(RetryDecision decision) {
        if (decision != RetryDecision.noRetry() &&
            exceptionFilter() == null && responseHeadersFilter() == null) {
            throw new IllegalStateException("Should set at least one retry rule if a backoff was set.");
        }
        return build(this, decision);
    }

    static RetryRule build(AbstractRetryRuleBuilder builder, RetryDecision decision) {
        final Predicate<RequestHeaders> requestHeadersFilter = builder.requestHeadersFilter();
        final Predicate<ResponseHeaders> responseHeaderFilter = builder.responseHeadersFilter();
        final Predicate<Throwable> exceptionFilter = builder.exceptionFilter();

        final CompletableFuture<RetryDecision> decisionFuture;
        if (decision == RetryDecision.DEFAULT) {
            decisionFuture = DEFAULT_DECISION;
        } else {
            decisionFuture = CompletableFuture.completedFuture(decision);
        }

        return (ctx, cause) -> {
            if (ctx.log().isAvailable(RequestLogProperty.REQUEST_HEADERS)) {
                final RequestHeaders requestHeaders = ctx.log().partial().requestHeaders();
                if (!requestHeadersFilter.test(requestHeaders)) {
                    return NEXT_DECISION;
                }
            }

            if (cause != null && exceptionFilter != null && exceptionFilter.test(Exceptions.peel(cause))) {
                return decisionFuture;
            }

            if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                final ResponseHeaders responseHeaders = ctx.log().partial().responseHeaders();
                if (responseHeaderFilter != null && responseHeaderFilter.test(responseHeaders)) {
                    return decisionFuture;
                }
            }

            return NEXT_DECISION;
        };
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("requestHeadersFilter", requestHeadersFilter())
                          .add("responseHeadersFilter", responseHeadersFilter())
                          .add("exceptionFilter", exceptionFilter())
                          .toString();
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public RetryRuleBuilder onResponseHeaders(
            Predicate<? super ResponseHeaders> responseHeadersFilter) {
        return (RetryRuleBuilder) super.onResponseHeaders(responseHeadersFilter);
    }

    @Override
    public RetryRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return (RetryRuleBuilder) super.onStatusClass(statusClasses);
    }

    @Override
    public RetryRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return (RetryRuleBuilder) super.onStatusClass(statusClasses);
    }

    @Override
    public RetryRuleBuilder onServerErrorStatus() {
        return (RetryRuleBuilder) super.onServerErrorStatus();
    }

    @Override
    public RetryRuleBuilder onStatus(HttpStatus... statuses) {
        return (RetryRuleBuilder) super.onStatus(statuses);
    }

    @Override
    public RetryRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        return (RetryRuleBuilder) super.onStatus(statuses);
    }

    @Override
    public RetryRuleBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        return (RetryRuleBuilder) super.onStatus(statusFilter);
    }

    @Override
    public RetryRuleBuilder onException(Class<? extends Throwable> exception) {
        return (RetryRuleBuilder) super.onException(exception);
    }

    @Override
    public RetryRuleBuilder onException(Predicate<? super Throwable> exceptionFilter) {
        return (RetryRuleBuilder) super.onException(exceptionFilter);
    }

    @Override
    public RetryRuleBuilder onException() {
        return (RetryRuleBuilder) super.onException();
    }

    @Override
    public RetryRuleBuilder onUnprocessed() {
        return (RetryRuleBuilder) super.onUnprocessed();
    }
}
