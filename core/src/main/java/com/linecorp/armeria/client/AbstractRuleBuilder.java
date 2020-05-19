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
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.UnstableApi;

/**
 * An abstract builder class which creates a {@link RetryRule}, a {@link RetryRuleWithContent},
 * {@link CircuitBreakerRule} or {@link CircuitBreakerRuleWithContent}.
 */
@UnstableApi
public abstract class AbstractRuleBuilder {

    private final Predicate<RequestHeaders> requestHeadersFilter;

    @Nullable
    private Predicate<ResponseHeaders> responseHeadersFilter;
    @Nullable
    private Predicate<Throwable> exceptionFilter;

    /**
     * Creates a new instance with the specified {@code requestHeadersFilter}.
     */
    @SuppressWarnings("unchecked")
    protected AbstractRuleBuilder(Predicate<? super RequestHeaders> requestHeadersFilter) {
        this.requestHeadersFilter = (Predicate<RequestHeaders>) requestHeadersFilter;
    }

    /**
     * Adds the specified {@code responseHeadersFilter} for a {@link RetryRule} or {@link CircuitBreakerRule}.
     */
    public AbstractRuleBuilder onResponseHeaders(
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
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} or {@link CircuitBreakerRule}.
     */
    public AbstractRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return onStatusClass(ImmutableSet.copyOf(requireNonNull(statusClasses, "statusClasses")));
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} or {@link CircuitBreakerRule}.
     */
    public AbstractRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        requireNonNull(statusClasses, "statusClasses");
        checkArgument(!Iterables.isEmpty(statusClasses), "statusClasses can't be empty.");

        final Set<HttpStatusClass> statusClasses0 = Sets.immutableEnumSet(statusClasses);
        onResponseHeaders(headers -> statusClasses0.contains(headers.status().codeClass()));
        return this;
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link RetryRule} or {@link CircuitBreakerRule}.
     */
    public AbstractRuleBuilder onServerErrorStatus() {
        return onStatusClass(HttpStatusClass.SERVER_ERROR);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} or {@link CircuitBreakerRule}.
     */
    public AbstractRuleBuilder onStatus(HttpStatus... statuses) {
        return onStatus(ImmutableSet.copyOf(requireNonNull(statuses, "statuses")));
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} or {@link CircuitBreakerRule}.
     */
    public AbstractRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        requireNonNull(statuses, "statuses");
        checkArgument(!Iterables.isEmpty(statuses), "statuses can't be empty.");

        final Set<HttpStatus> statuses0 = ImmutableSet.copyOf(statuses);
        onResponseHeaders(headers -> statuses0.contains(headers.status()));
        return this;
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link RetryRule} or {@link CircuitBreakerRule}.
     */
    public AbstractRuleBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        requireNonNull(statusFilter, "statusFilter");
        onResponseHeaders(headers -> statusFilter.test(headers.status()));
        return this;
    }

    /**
     * Adds the specified exception type for a {@link RetryRule} or {@link CircuitBreakerRule}.
     */
    public AbstractRuleBuilder onException(Class<? extends Throwable> exception) {
        requireNonNull(exception, "exception");
        return onException(exception::isInstance);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link RetryRule} or {@link CircuitBreakerRule}.
     */
    public AbstractRuleBuilder onException(Predicate<? super Throwable> exceptionFilter) {
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
     * Makes a {@link RetryRule} or a {@link CircuitBreakerRule} perform on any {@link Exception}.
     */
    public AbstractRuleBuilder onException() {
        return onException(unused -> true);
    }

    /**
     * Makes a {@link RetryRule} or a {@link CircuitBreakerRule} perform on
     * an {@link UnprocessedRequestException}.
     */
    public AbstractRuleBuilder onUnprocessed() {
        return onException(UnprocessedRequestException.class);
    }

    /**
     * Returns the {@link Predicate} of a {@link RequestHeaders}.
     */
    public final Predicate<RequestHeaders> requestHeadersFilter() {
        return requestHeadersFilter;
    }

    /**
     * Returns the {@link Predicate} of a {@link ResponseHeaders}.
     */
    @Nullable
    public final Predicate<ResponseHeaders> responseHeadersFilter() {
        return responseHeadersFilter;
    }

    /**
     * Returns the {@link Predicate} of an {@link Exception}.
     */
    @Nullable
    public final Predicate<Throwable> exceptionFilter() {
        return exceptionFilter;
    }
}
