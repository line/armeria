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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * An abstract builder class which creates a {@link RetryRule} or a {@link RetryRuleWithContent}.
 */
abstract class AbstractRetryRuleBuilder {

    static final CompletableFuture<RetryDecision> NEXT_DECISION =
            CompletableFuture.completedFuture(RetryDecision.next());
    static final CompletableFuture<RetryDecision> DEFAULT_DECISION =
            CompletableFuture.completedFuture(RetryDecision.DEFAULT);

    private final Predicate<RequestHeaders> requestHeadersFilter;

    @Nullable
    private Predicate<ResponseHeaders> responseHeadersFilter;
    @Nullable
    private Predicate<Throwable> exceptionFilter;

    @SuppressWarnings("unchecked")
    AbstractRetryRuleBuilder(Predicate<? super RequestHeaders> requestHeadersFilter) {
        this.requestHeadersFilter = (Predicate<RequestHeaders>) requestHeadersFilter;
    }

    /**
     * Adds the specified {@code responseHeadersFilter} for a {@link RetryRule} which will retry
     * if the {@code responseHeadersFilter} returns {@code true}.
     */
    public AbstractRetryRuleBuilder onResponseHeaders(
            Predicate<? super ResponseHeaders> responseHeadersFilter) {
        requireNonNull(responseHeadersFilter, "responseHeadersFilter");
        if (this.responseHeadersFilter != null) {
            this.responseHeadersFilter = this.responseHeadersFilter.or(responseHeadersFilter);
        } else {
            @SuppressWarnings("unchecked")
            final Predicate<ResponseHeaders> cast = (Predicate<ResponseHeaders>) responseHeadersFilter;
            this.responseHeadersFilter = cast;
        }
        return this;
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public AbstractRetryRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return onStatusClass(ImmutableSet.copyOf(requireNonNull(statusClasses, "statusClasses")));
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public AbstractRetryRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        requireNonNull(statusClasses, "statusClasses");
        checkArgument(!Iterables.isEmpty(statusClasses), "statusClasses can't be empty.");

        final Set<HttpStatusClass> statusClasses0 = Sets.immutableEnumSet(statusClasses);
        onResponseHeaders(headers -> statusClasses0.contains(headers.status().codeClass()));
        return this;
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link RetryRule} which will retry
     * if the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    public AbstractRetryRuleBuilder onServerErrorStatus() {
        return onStatusClass(HttpStatusClass.SERVER_ERROR);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public AbstractRetryRuleBuilder onStatus(HttpStatus... statuses) {
        return onStatus(ImmutableSet.copyOf(requireNonNull(statuses, "statuses")));
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public AbstractRetryRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        requireNonNull(statuses, "statuses");
        checkArgument(!Iterables.isEmpty(statuses), "statuses can't be empty.");

        final Set<HttpStatus> statuses0 = ImmutableSet.copyOf(statuses);
        onResponseHeaders(headers -> statuses0.contains(headers.status()));
        return this;
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link RetryRule} which will retry
     * if the response status matches the specified {@code statusFilter}.
     */
    public AbstractRetryRuleBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        requireNonNull(statusFilter, "statusFilter");
        onResponseHeaders(headers -> statusFilter.test(headers.status()));
        return this;
    }

    /**
     * Adds the specified exception type for a {@link RetryRule} which will retry
     * if an {@link Exception} is raised and that is instance of the specified {@code exception}.
     */
    public AbstractRetryRuleBuilder onException(Class<? extends Throwable> exception) {
        requireNonNull(exception, "exception");
        return onException(exception::isInstance);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link RetryRule} which will retry
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    public AbstractRetryRuleBuilder onException(Predicate<? super Throwable> exceptionFilter) {
        requireNonNull(exceptionFilter, "exceptionFilter");
        if (this.exceptionFilter != null) {
            this.exceptionFilter = this.exceptionFilter.or(exceptionFilter);
        } else {
            @SuppressWarnings("unchecked")
            final Predicate<Throwable> cast = (Predicate<Throwable>) exceptionFilter;
            this.exceptionFilter = cast;
        }
        return this;
    }

    /**
     * Makes a {@link RetryRule} retry on any {@link Exception}.
     */
    public AbstractRetryRuleBuilder onException() {
        return onException(unused -> true);
    }

    /**
     * Makes a {@link RetryRule} retry on an {@link UnprocessedRequestException} which means that the request
     * has not been processed by the server. Therefore, you can safely retry the request without worrying about
     * the idempotency of the request.
     */
    public AbstractRetryRuleBuilder onUnprocessed() {
        return onException(UnprocessedRequestException.class);
    }

    Predicate<RequestHeaders> requestHeadersFilter() {
        return requestHeadersFilter;
    }

    @Nullable
    Predicate<ResponseHeaders> responseHeadersFilter() {
        return responseHeadersFilter;
    }

    @Nullable
    Predicate<Throwable> exceptionFilter() {
        return exceptionFilter;
    }
}
