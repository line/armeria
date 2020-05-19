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
import java.util.function.BiFunction;
import java.util.function.Predicate;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.AbstractRuleBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.client.AbstractRuleBuilderUtil;

/**
 * A builder for creating a new {@link RetryRule}.
 */
public final class RetryRuleBuilder extends AbstractRuleBuilder {

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

    private RetryRule build(RetryDecision decision) {
        if (decision != RetryDecision.noRetry() &&
            exceptionFilter() == null && responseHeadersFilter() == null) {
            throw new IllegalStateException("Should set at least one retry rule if a backoff was set.");
        }
        return build(this, decision, false);
    }

    static RetryRule build(AbstractRuleBuilder builder, RetryDecision decision, boolean hasResponseFilter) {
        final BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> filter =
                AbstractRuleBuilderUtil.buildFilter(builder, hasResponseFilter);

        final CompletableFuture<RetryDecision> decisionFuture;
        if (decision == RetryDecision.DEFAULT) {
            decisionFuture = DEFAULT_DECISION;
        } else {
            decisionFuture = CompletableFuture.completedFuture(decision);
        }
        return filter.andThen(matched -> matched ? decisionFuture : NEXT_DECISION)::apply;
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

    /**
     * Adds the specified {@code responseHeadersFilter} for a {@link RetryRule} which will retry
     * if the {@code responseHeadersFilter} returns {@code true}.
     */
    @Override
    public RetryRuleBuilder onResponseHeaders(
            Predicate<? super ResponseHeaders> responseHeadersFilter) {
        return (RetryRuleBuilder) super.onResponseHeaders(responseHeadersFilter);
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
    public RetryRuleBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        return (RetryRuleBuilder) super.onStatus(statusFilter);
    }

    /**
     * Adds the specified exception type for a {@link RetryRule} which will retry
     * if an {@link Exception} is raised and that is instance of the specified {@code exception}.
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
    public RetryRuleBuilder onException(Predicate<? super Throwable> exceptionFilter) {
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
