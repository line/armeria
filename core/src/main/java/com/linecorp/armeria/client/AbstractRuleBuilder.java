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

package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A skeletal builder implementation for {@link RetryRule}, {@link RetryRuleWithContent},
 * {@link CircuitBreakerRule} and {@link CircuitBreakerRuleWithContent}.
 */
@UnstableApi
public abstract class AbstractRuleBuilder {

    private final BiPredicate<ClientRequestContext, RequestHeaders> requestHeadersFilter;

    @Nullable
    private BiPredicate<ClientRequestContext, ResponseHeaders> responseHeadersFilter;
    @Nullable
    private BiPredicate<ClientRequestContext, HttpHeaders> responseTrailersFilter;
    @Nullable
    private BiPredicate<ClientRequestContext, Throwable> exceptionFilter;

    /**
     * Creates a new instance with the specified {@code requestHeadersFilter}.
     */
    @SuppressWarnings("unchecked")
    protected AbstractRuleBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        this.requestHeadersFilter = (BiPredicate<ClientRequestContext, RequestHeaders>) requestHeadersFilter;
    }

    /**
     * Adds the specified {@code responseHeadersFilter}.
     */
    public AbstractRuleBuilder onResponseHeaders(
            BiPredicate<? super ClientRequestContext, ? super ResponseHeaders> responseHeadersFilter) {
        requireNonNull(responseHeadersFilter, "responseHeadersFilter");
        if (this.responseHeadersFilter != null) {
            this.responseHeadersFilter = this.responseHeadersFilter.or(responseHeadersFilter);
        } else {
            @SuppressWarnings("unchecked")
            final BiPredicate<ClientRequestContext, ResponseHeaders> cast =
                    (BiPredicate<ClientRequestContext, ResponseHeaders>) responseHeadersFilter;
            this.responseHeadersFilter = cast;
        }
        return this;
    }

    /**
     * Adds the specified {@code responseTrailersFilter}.
     */
    public AbstractRuleBuilder onResponseTrailers(
            BiPredicate<? super ClientRequestContext, ? super HttpHeaders> responseTrailersFilter) {
        requireNonNull(responseTrailersFilter, "responseTrailersFilter");
        if (this.responseTrailersFilter != null) {
            this.responseTrailersFilter = this.responseTrailersFilter.or(responseTrailersFilter);
        } else {
            @SuppressWarnings("unchecked")
            final BiPredicate<ClientRequestContext, HttpHeaders> cast =
                    (BiPredicate<ClientRequestContext, HttpHeaders>) responseTrailersFilter;
            this.responseTrailersFilter = cast;
        }
        return this;
    }

    /**
     * Adds the specified {@link HttpStatusClass}es.
     */
    public AbstractRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return onStatusClass(ImmutableSet.copyOf(requireNonNull(statusClasses, "statusClasses")));
    }

    /**
     * Adds the specified {@link HttpStatusClass}es.
     */
    public AbstractRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        requireNonNull(statusClasses, "statusClasses");
        checkArgument(!Iterables.isEmpty(statusClasses), "statusClasses can't be empty.");

        final Set<HttpStatusClass> statusClasses0 = Sets.immutableEnumSet(statusClasses);
        onResponseHeaders((ctx, headers) -> statusClasses0.contains(headers.status().codeClass()));
        return this;
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR}.
     */
    public AbstractRuleBuilder onServerErrorStatus() {
        return onStatusClass(HttpStatusClass.SERVER_ERROR);
    }

    /**
     * Adds the specified {@link HttpStatus}es.
     */
    public AbstractRuleBuilder onStatus(HttpStatus... statuses) {
        return onStatus(ImmutableSet.copyOf(requireNonNull(statuses, "statuses")));
    }

    /**
     * Adds the specified {@link HttpStatus}es.
     */
    public AbstractRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        requireNonNull(statuses, "statuses");
        checkArgument(!Iterables.isEmpty(statuses), "statuses can't be empty.");

        final Set<HttpStatus> statuses0 = ImmutableSet.copyOf(statuses);
        onResponseHeaders((ctx, headers) -> statuses0.contains(headers.status()));
        return this;
    }

    /**
     * Adds the specified {@code statusFilter}.
     */
    public AbstractRuleBuilder onStatus(
            BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter) {
        requireNonNull(statusFilter, "statusFilter");
        onResponseHeaders((ctx, headers) -> statusFilter.test(ctx, headers.status()));
        return this;
    }

    /**
     * Adds the specified exception type.
     */
    public AbstractRuleBuilder onException(Class<? extends Throwable> exception) {
        requireNonNull(exception, "exception");
        return onException((unused, ex) -> exception.isInstance(ex));
    }

    /**
     * Adds the specified {@code exceptionFilter}.
     */
    public AbstractRuleBuilder onException(
            BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter) {
        requireNonNull(exceptionFilter, "exceptionFilter");
        if (this.exceptionFilter != null) {
            this.exceptionFilter = this.exceptionFilter.or(exceptionFilter);
        } else {
            @SuppressWarnings("unchecked")
            final BiPredicate<ClientRequestContext, Throwable> cast =
                    (BiPredicate<ClientRequestContext, Throwable>) exceptionFilter;
            this.exceptionFilter = cast;
        }
        return this;
    }

    /**
     * Adds any {@link Exception}.
     */
    public AbstractRuleBuilder onException() {
        return onException((unused1, unused2) -> true);
    }

    /**
     * Adds an {@link UnprocessedRequestException}.
     */
    public AbstractRuleBuilder onUnprocessed() {
        return onException(UnprocessedRequestException.class);
    }

    /**
     * Returns the {@link Predicate} of a {@link RequestHeaders}.
     */
    protected final BiPredicate<ClientRequestContext, RequestHeaders> requestHeadersFilter() {
        return requestHeadersFilter;
    }

    /**
     * Returns the {@link Predicate} of a {@link ResponseHeaders}.
     */
    @Nullable
    protected final BiPredicate<ClientRequestContext, ResponseHeaders> responseHeadersFilter() {
        return responseHeadersFilter;
    }

    /**
     * Returns the {@link Predicate} of a response trailers.
     */
    @Nullable
    protected final BiPredicate<ClientRequestContext, HttpHeaders> responseTrailersFilter() {
        return responseTrailersFilter;
    }

    /**
     * Returns the {@link Predicate} of an {@link Exception}.
     */
    @Nullable
    protected final BiPredicate<ClientRequestContext, Throwable> exceptionFilter() {
        return exceptionFilter;
    }
}
