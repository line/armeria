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

import java.time.Duration;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A skeletal builder implementation for {@link RetryRule}, {@link RetryRuleWithContent},
 * {@link CircuitBreakerRule} and {@link CircuitBreakerRuleWithContent}.
 */
@UnstableApi
public abstract class AbstractRuleBuilder<SELF extends AbstractRuleBuilder<SELF>> {

    private final BiPredicate<ClientRequestContext, RequestHeaders> requestHeadersFilter;

    @Nullable
    private BiPredicate<ClientRequestContext, ResponseHeaders> responseHeadersFilter;
    @Nullable
    private BiPredicate<ClientRequestContext, HttpHeaders> responseTrailersFilter;
    @Nullable
    private BiPredicate<ClientRequestContext, Throwable> exceptionFilter;
    @Nullable
    private BiPredicate<ClientRequestContext, HttpHeaders> grpcTrailersFilter;
    @Nullable
    private BiPredicate<ClientRequestContext, Duration> totalDurationFilter;

    /**
     * Creates a new instance with the specified {@code requestHeadersFilter}.
     */
    @SuppressWarnings("unchecked")
    protected AbstractRuleBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        this.requestHeadersFilter = (BiPredicate<ClientRequestContext, RequestHeaders>) requestHeadersFilter;
    }

    @SuppressWarnings("unchecked")
    final SELF self() {
        return (SELF) this;
    }

    /**
     * Adds the specified {@code responseHeadersFilter}.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the specified {@code responseHeadersFilter}
     * returns {@code true}, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onResponseHeaders(
            BiPredicate<? super ClientRequestContext, ? super ResponseHeaders> responseHeadersFilter) {
        this.responseHeadersFilter = combinePredicates(this.responseHeadersFilter, responseHeadersFilter,
                                                       "responseHeadersFilter");
        return self();
    }

    /**
     * Adds the specified {@code responseTrailersFilter}. Note that using this method makes the entire
     * response buffered, which may lead to excessive memory usage.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the specified {@code responseTrailersFilter}
     * returns {@code true}, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onResponseTrailers(
            BiPredicate<? super ClientRequestContext, ? super HttpHeaders> responseTrailersFilter) {
        this.responseTrailersFilter = combinePredicates(this.responseTrailersFilter, responseTrailersFilter,
                                                        "responseTrailersFilter");
        return self();
    }

    /**
     * Adds the specified {@code grpcTrailersFilter}. Note that using this method makes the entire
     * response buffered, which may lead to excessive memory usage.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the specified {@code grpcTrailersFilter}
     * returns {@code true}, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onGrpcTrailers(
            BiPredicate<? super ClientRequestContext, ? super HttpHeaders> grpcTrailersFilter) {
        this.grpcTrailersFilter = combinePredicates(this.grpcTrailersFilter, grpcTrailersFilter,
                                                    "grpcTrailersFilter");
        return self();
    }

    /**
     * Adds the specified {@link HttpStatusClass}es.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the response status is one of the specified
     * {@link HttpStatusClass}es, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onStatusClass(HttpStatusClass... statusClasses) {
        return onStatusClass(ImmutableSet.copyOf(requireNonNull(statusClasses, "statusClasses")));
    }

    /**
     * Adds the specified {@link HttpStatusClass}es.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the response status is one of the specified
     * {@link HttpStatusClass}es, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        requireNonNull(statusClasses, "statusClasses");
        checkArgument(!Iterables.isEmpty(statusClasses), "statusClasses can't be empty.");

        final Set<HttpStatusClass> statusClasses0 = Sets.immutableEnumSet(statusClasses);
        onResponseHeaders((ctx, headers) -> statusClasses0.contains(headers.status().codeClass()));
        return self();
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR}.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the response status is
     * {@link HttpStatusClass#SERVER_ERROR}, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onServerErrorStatus() {
        return onStatusClass(HttpStatusClass.SERVER_ERROR);
    }

    /**
     * Adds the specified {@link HttpStatus}es.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the response status is one of the specified
     * {@link HttpStatus}es, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onStatus(HttpStatus... statuses) {
        return onStatus(ImmutableSet.copyOf(requireNonNull(statuses, "statuses")));
    }

    /**
     * Adds the specified {@link HttpStatus}es.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the response status is one of the specified
     * {@link HttpStatus}es, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onStatus(Iterable<HttpStatus> statuses) {
        requireNonNull(statuses, "statuses");
        checkArgument(!Iterables.isEmpty(statuses), "statuses can't be empty.");

        final Set<HttpStatus> statuses0 = ImmutableSet.copyOf(statuses);
        onResponseHeaders((ctx, headers) -> statuses0.contains(headers.status()));
        return self();
    }

    /**
     * Adds the specified {@code statusFilter}.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the response status  matches the specified
     * {@code statusFilter}, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onStatus(BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter) {
        requireNonNull(statusFilter, "statusFilter");
        onResponseHeaders((ctx, headers) -> statusFilter.test(ctx, headers.status()));
        return self();
    }

    /**
     * Adds the specified exception type.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the raised exception is an instance of the
     * specified {@code exception}, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onException(Class<? extends Throwable> exception) {
        requireNonNull(exception, "exception");
        return onException((unused, ex) -> exception.isInstance(ex));
    }

    /**
     * Adds the specified {@code exceptionFilter}.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the specified {@code exceptionFilter} returns
     * {@code true} for the raised exception, a {@link Response} is reported as a success or failure to a
     * {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onException(BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter) {
        this.exceptionFilter = combinePredicates(this.exceptionFilter, exceptionFilter, "exceptionFilter");
        return self();
    }

    /**
     * Adds any {@link Exception}.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, a {@link Response} is reported as a success or
     * failure to a {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onException() {
        return onException((unused1, unused2) -> true);
    }

    /**
     * Adds {@link TimeoutException}.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, a {@link Response} is reported as a success or
     * failure to a {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onTimeoutException() {
        return onException((ctx, ex) -> {
            if (ctx.isTimedOut()) {
                return true;
            }
            return ex instanceof TimeoutException ||
                   ex instanceof UnprocessedRequestException && ex.getCause() instanceof TimeoutException;
        });
    }

    private static <T> BiPredicate<ClientRequestContext, T> combinePredicates(
            @Nullable BiPredicate<ClientRequestContext, T> firstPredicate,
            BiPredicate<? super ClientRequestContext, ? super T> secondPredicate,
            String paramName) {

        requireNonNull(secondPredicate, paramName);
        if (firstPredicate != null) {
            return firstPredicate.or(secondPredicate);
        }

        @SuppressWarnings("unchecked")
        final BiPredicate<ClientRequestContext, T> cast =
                (BiPredicate<ClientRequestContext, T>) secondPredicate;
        return cast;
    }

    /**
     * Adds an {@link UnprocessedRequestException}.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, a {@link Response} is reported as a success or
     * failure to a {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onUnprocessed() {
        return onException(UnprocessedRequestException.class);
    }

    /**
     * Adds the specified {@code totalDurationFilter}.
     *
     * <p>When this is used for a {@link CircuitBreakerRule}, and the specified {@code totalDurationFilter}
     * returns {@code true}, a {@link Response} is reported as a success or
     * failure to a {@link CircuitBreaker} or ignored depending on the build methods({@code #thenSuccess()},
     * {@code #thenFailure()} and {@code #thenIgnore()}).
     */
    public SELF onTotalDuration(
            BiPredicate<? super ClientRequestContext, ? super Duration> totalDurationFilter) {
        this.totalDurationFilter = combinePredicates(this.totalDurationFilter, totalDurationFilter,
                                                     "totalDurationFilter");
        return self();
    }

    /**
     * Returns the {@link BiPredicate} of a {@link RequestHeaders}.
     */
    protected final BiPredicate<ClientRequestContext, RequestHeaders> requestHeadersFilter() {
        return requestHeadersFilter;
    }

    /**
     * Returns the {@link BiPredicate} of a {@link ResponseHeaders}.
     */
    @Nullable
    protected final BiPredicate<ClientRequestContext, ResponseHeaders> responseHeadersFilter() {
        return responseHeadersFilter;
    }

    /**
     * Returns the {@link BiPredicate} of a response trailers.
     */
    @Nullable
    protected final BiPredicate<ClientRequestContext, HttpHeaders> responseTrailersFilter() {
        return responseTrailersFilter;
    }

    /**
     * Returns the {@link BiPredicate} of an {@link Exception}.
     */
    @Nullable
    protected final BiPredicate<ClientRequestContext, Throwable> exceptionFilter() {
        return exceptionFilter;
    }

    /**
     * Returns the {@link BiPredicate} of gRPC trailers.
     */
    @Nullable
    protected final BiPredicate<ClientRequestContext, HttpHeaders> grpcTrailersFilter() {
        return grpcTrailersFilter;
    }

    /**
     * Returns then {@link Predicate} of total duration.
     */
    @Nullable
    protected final BiPredicate<ClientRequestContext, Duration> totalDurationFilter() {
        return totalDurationFilter;
    }

    /**
     * Returns whether this rule being built requires HTTP response trailers.
     */
    protected final boolean requiresResponseTrailers() {
        return responseTrailersFilter != null ||
               grpcTrailersFilter != null ||
               totalDurationFilter != null;
    }
}
