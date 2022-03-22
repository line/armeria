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
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.AbstractRuleBuilderUtil;

/**
 * A builder for creating a new {@link RetryRule}.
 */
public final class RetryRuleBuilder extends AbstractRuleBuilder {

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
            exceptionFilter() == null && responseHeadersFilter() == null && responseTrailersFilter() == null) {
            throw new IllegalStateException("Should set at least one retry rule if a backoff was set.");
        }
        final BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> ruleFilter =
                AbstractRuleBuilderUtil.buildFilter(requestHeadersFilter(), responseHeadersFilter(),
                                                    responseTrailersFilter(), exceptionFilter(), false);
        return build(ruleFilter, decision, responseTrailersFilter() != null);
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

    // Override the return type of the chaining methods in the superclass.

    /**
     * Adds the specified {@code responseHeadersFilter} for a {@link RetryRule} which will retry
     * if the {@code responseHeadersFilter} returns {@code true}.
     */
    @Override
    public RetryRuleBuilder onResponseHeaders(
            BiPredicate<? super ClientRequestContext, ? super ResponseHeaders> responseHeadersFilter) {
        return (RetryRuleBuilder) super.onResponseHeaders(responseHeadersFilter);
    }

    /**
     * Adds the specified {@code responseTrailersFilter} for a {@link RetryRuleWithContent} which will retry
     * if the {@code responseTrailersFilter} returns {@code true}. Note that using this method makes the entire
     * response buffered, which may lead to excessive memory usage.
     */
    @Override
    public RetryRuleBuilder onResponseTrailers(
            BiPredicate<? super ClientRequestContext, ? super HttpHeaders> responseTrailersFilter) {
        return (RetryRuleBuilder) super.onResponseTrailers(responseTrailersFilter);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} which will retry
     * if a class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    @Override
    public RetryRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return (RetryRuleBuilder) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} which will retry
     * if a class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    @Override
    public RetryRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return (RetryRuleBuilder) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link RetryRule} which will retry
     * if a class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    @Override
    public RetryRuleBuilder onServerErrorStatus() {
        return (RetryRuleBuilder) super.onServerErrorStatus();
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} which will retry
     * if a response status is one of the specified {@link HttpStatus}es.
     */
    @Override
    public RetryRuleBuilder onStatus(HttpStatus... statuses) {
        return (RetryRuleBuilder) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} which will retry
     * if a response status is one of the specified {@link HttpStatus}es.
     */
    @Override
    public RetryRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        return (RetryRuleBuilder) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link RetryRule} which will retry
     * if a response status matches the specified {@code statusFilter}.
     */
    @Override
    public RetryRuleBuilder onStatus(
            BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter) {
        return (RetryRuleBuilder) super.onStatus(statusFilter);
    }

    /**
     * Adds the specified exception type for a {@link RetryRule} which will retry
     * if an {@link Exception} is raised and it is an instance of the specified {@code exception}.
     */
    @Override
    public RetryRuleBuilder onException(Class<? extends Throwable> exception) {
        return (RetryRuleBuilder) super.onException(exception);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link RetryRule} which will retry
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    @Override
    public RetryRuleBuilder onException(
            BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter) {
        return (RetryRuleBuilder) super.onException(exceptionFilter);
    }

    /**
     * Makes a {@link RetryRule} retry on any {@link Exception}.
     */
    @Override
    public RetryRuleBuilder onException() {
        return (RetryRuleBuilder) super.onException();
    }

    /**
     * Makes a {@link RetryRule} retry on an {@link UnprocessedRequestException} which means that the request
     * has not been processed by the server. Therefore, you can safely retry the request without worrying about
     * the idempotency of the request.
     */
    @Override
    public RetryRuleBuilder onUnprocessed() {
        return (RetryRuleBuilder) super.onUnprocessed();
    }
}
