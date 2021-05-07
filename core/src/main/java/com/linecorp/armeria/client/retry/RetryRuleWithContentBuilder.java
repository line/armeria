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
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.client.AbstractRuleBuilderUtil;

/**
 * A builder for creating a new {@link RetryRuleWithContent}.
 */
public final class RetryRuleWithContentBuilder<T extends Response> extends AbstractRuleWithContentBuilder<T> {

    RetryRuleWithContentBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Adds the specified {@code responseFilter} for a {@link RetryRuleWithContent} which will retry
     * if the specified {@code responseFilter} completes with {@code true}.
     */
    @Override
    public RetryRuleWithContentBuilder<T> onResponse(
            BiFunction<? super ClientRequestContext, ? super T,
                    ? extends CompletionStage<Boolean>> responseFilter) {
        return (RetryRuleWithContentBuilder<T>) super.onResponse(responseFilter);
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
            !hasResponseFilter) {
            throw new IllegalStateException("Should set at least one retry rule if a backoff was set.");
        }

        final BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> ruleFilter =
                AbstractRuleBuilderUtil.buildFilter(requestHeadersFilter(), responseHeadersFilter(),
                                                    responseTrailersFilter(), exceptionFilter(),
                                                    hasResponseFilter);

        final RetryRule first = RetryRuleBuilder.build(ruleFilter, decision, responseTrailersFilter() != null);
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

    // Override the return type of the chaining methods in the superclass.

    /**
     * Adds the specified {@code responseHeadersFilter} for a {@link RetryRuleWithContent} which will retry
     * if the {@code responseHeadersFilter} returns {@code true}.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onResponseHeaders(
            BiPredicate<? super ClientRequestContext, ? super ResponseHeaders> responseHeadersFilter) {
        return (RetryRuleWithContentBuilder<T>) super.onResponseHeaders(responseHeadersFilter);
    }

    /**
     * Adds the specified {@code responseTrailersFilter} for a {@link RetryRuleWithContent} which will retry
     * if the {@code responseTrailersFilter} returns {@code true}. Note that using this method makes the entire
     * response buffered, which may lead to excessive memory usage.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onResponseTrailers(
            BiPredicate<? super ClientRequestContext, ? super HttpHeaders> responseTrailersFilter) {
        return (RetryRuleWithContentBuilder<T>) super.onResponseTrailers(responseTrailersFilter);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRuleWithContent} which will retry
     * if a class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatusClass(HttpStatusClass... statusClasses) {
        return (RetryRuleWithContentBuilder<T>) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRuleWithContent} which will retry
     * if a class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return (RetryRuleWithContentBuilder<T>) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link RetryRuleWithContent} which will retry
     * if a class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onServerErrorStatus() {
        return (RetryRuleWithContentBuilder<T>) super.onServerErrorStatus();
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRuleWithContent} which will retry
     * if a response status is one of the specified {@link HttpStatus}es.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatus(HttpStatus... statuses) {
        return (RetryRuleWithContentBuilder<T>) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRuleWithContent} which will retry
     * if a response status is one of the specified {@link HttpStatus}es.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatus(Iterable<HttpStatus> statuses) {
        return (RetryRuleWithContentBuilder<T>) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link RetryRuleWithContent} which will retry
     * if a response status matches the specified {@code statusFilter}.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onStatus(
            BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter) {
        return (RetryRuleWithContentBuilder<T>) super.onStatus(statusFilter);
    }

    /**
     * Adds the specified exception type for a {@link RetryRuleWithContent} which will retry
     * if an {@link Exception} is raised and it is an instance of the specified {@code exception}.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onException(Class<? extends Throwable> exception) {
        return (RetryRuleWithContentBuilder<T>) super.onException(exception);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link RetryRuleWithContent} which will retry
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onException(
            BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter) {
        return (RetryRuleWithContentBuilder<T>) super.onException(exceptionFilter);
    }

    /**
     * Makes a {@link RetryRuleWithContent} retry on any {@link Exception}.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onException() {
        return (RetryRuleWithContentBuilder<T>) super.onException();
    }

    /**
     * Makes a {@link RetryRuleWithContent} retry on an {@link UnprocessedRequestException} which means that
     * the request has not been processed by the server.
     * Therefore, you can safely retry the request without worrying about the idempotency of the request.
     */
    @SuppressWarnings("unchecked")
    @Override
    public RetryRuleWithContentBuilder<T> onUnprocessed() {
        return (RetryRuleWithContentBuilder<T>) super.onUnprocessed();
    }
}
