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

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Determines whether a failed request should be retried using the content of a {@link Response}.
 * If you just need the headers to make a decision, use {@link RetryRule} for efficiency.
 *
 * @param <T> the response type
 */
@FunctionalInterface
public interface RetryRuleWithContent<T extends Response> {

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with
     * the {@linkplain Backoff#ofDefault() default backoff} if the specified {@code retryFunction} completes
     * with {@code true}.
     */
    static <T extends Response> RetryRuleWithContent<T> onResponse(
            BiFunction<? super ClientRequestContext, ? super T,
                    ? extends CompletionStage<Boolean>> retryFunction) {
        return onResponse(retryFunction, Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the specified {@link Backoff}
     * if the specified {@code retryFunction} completes with {@code true}.
     */
    static <T extends Response> RetryRuleWithContent<T> onResponse(
            BiFunction<? super ClientRequestContext, ? super T,
                    ? extends CompletionStage<Boolean>> retryFunction,
            Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onResponse(retryFunction).thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the class of the response status is the specified
     * {@link HttpStatusClass}.
     */
    static <T extends Response> RetryRuleWithContent<T> onStatusClass(HttpStatusClass statusClass) {
        return onStatusClass(statusClass, Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the specified {@link Backoff}
     * if the class of the response status is the specified {@link HttpStatusClass}.
     */
    static <T extends Response> RetryRuleWithContent<T> onStatusClass(HttpStatusClass statusClass,
                                                                      Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onStatusClass(statusClass).thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the class of the response status is the specified
     * {@link HttpStatusClass}es.
     */
    static <T extends Response> RetryRuleWithContent<T> onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return onStatusClass(statusClasses, Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the specified {@link Backoff}
     * if the class of the response status is the specified {@link HttpStatusClass}es.
     */
    static <T extends Response> RetryRuleWithContent<T> onStatusClass(Iterable<HttpStatusClass> statusClasses,
                                                                      Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onStatusClass(statusClasses).thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the class of the response status is
     * {@link HttpStatusClass#SERVER_ERROR}.
     */
    static <T extends Response> RetryRuleWithContent<T> onServerErrorStatus() {
        return onServerErrorStatus(Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the specified {@link Backoff}
     * if the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    static <T extends Response> RetryRuleWithContent<T> onServerErrorStatus(Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onServerErrorStatus().thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the response status is one of the specified
     * {@link HttpStatus}es.
     */
    static <T extends Response> RetryRuleWithContent<T> onStatus(HttpStatus... statuses) {
        return RetryRuleWithContent.<T>builder().onStatus(statuses).thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the response status is one of the specified
     * {@link HttpStatus}es.
     */
    static <T extends Response> RetryRuleWithContent<T> onStatus(Iterable<HttpStatus> statuses) {
        return onStatus(statuses, Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the specified {@link Backoff}
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    static <T extends Response> RetryRuleWithContent<T> onStatus(Iterable<HttpStatus> statuses,
                                                                 Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onStatus(statuses).thenBackoff(backoff);
    }

    /**
     * Returns a newly created a {@link RetryRuleWithContent} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the response status matches the specified
     * {@code statusFilter}.
     */
    static <T extends Response> RetryRuleWithContent<T> onStatus(
            BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter) {
        return onStatus(statusFilter, Backoff.ofDefault());
    }

    /**
     * Returns a newly created a {@link RetryRuleWithContent} that will retry with the specified
     * {@link Backoff} if the response status matches the specified {@code statusFilter}.
     */
    static <T extends Response> RetryRuleWithContent<T> onStatus(
            BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter, Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onStatus(statusFilter).thenBackoff(backoff);
    }

    /**
     * Returns a newly created a {@link RetryRuleWithContent} that will retry with
     * the {@linkplain Backoff#ofDefault() default backoff} if an {@link Exception} is raised and that is an
     * instance of the specified {@code exception}.
     */
    static <T extends Response> RetryRuleWithContent<T> onException(Class<? extends Throwable> exception) {
        return onException(exception, Backoff.ofDefault());
    }

    /**
     * Returns a newly created a {@link RetryRuleWithContent} that will retry with the specified
     * {@link Backoff} if an {@link Exception} is raised and that is an instance of the specified
     * {@code exception}.
     */
    static <T extends Response> RetryRuleWithContent<T> onException(Class<? extends Throwable> exception,
                                                                    Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onException(exception).thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if an {@link Exception} is raised and the specified
     * {@code exceptionFilter} returns {@code true}.
     */
    static <T extends Response> RetryRuleWithContent<T> onException(
            BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter) {
        return onException(exceptionFilter, Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that will retry with the specified {@link Backoff}
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    static <T extends Response> RetryRuleWithContent<T> onException(
            BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter, Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onException(exceptionFilter).thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that retries with
     * {@linkplain Backoff#ofDefault() default backoff} on any {@link Exception}.
     * Note that this rule should be used carefully because it reties regardless of
     * <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotency</a>.
     */
    static <T extends Response> RetryRuleWithContent<T> onException() {
        return onException(Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRuleWithContent} that retries with the specified {@link Backoff} on
     * any {@link Exception}.
     * Note that this rule should be used carefully because it reties regardless of
     * <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotency</a>.
     */
    static <T extends Response> RetryRuleWithContent<T> onException(Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onException().thenBackoff(backoff);
    }

    /**
     * Returns a {@link RetryRuleWithContent} that retries with the
     * {@linkplain Backoff#ofDefault() default backoff} on an {@link UnprocessedRequestException} which means
     * that the request has not been processed by the server. Therefore, you can safely retry the request
     * without worrying about the idempotency of the request.
     */
    static <T extends Response> RetryRuleWithContent<T> onUnprocessed() {
        return onUnprocessed(Backoff.ofDefault());
    }

    /**
     * Returns a {@link RetryRuleWithContent} that retries with the specified {@link Backoff} on an
     * {@link UnprocessedRequestException} which means that the request has not been processed by the server.
     * Therefore, you can safely retry the request without worrying about the idempotency of the request.
     */
    static <T extends Response> RetryRuleWithContent<T> onUnprocessed(Backoff backoff) {
        return RetryRuleWithContent.<T>builder().onUnprocessed().thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRuleWithContentBuilder}.
     */
    static <T extends Response> RetryRuleWithContentBuilder<T> builder() {
        return builder(HttpMethod.knownMethods());
    }

    /**
     * Returns a newly created {@link RetryRuleWithContentBuilder} with the specified {@link HttpMethod}s.
     */
    static <T extends Response> RetryRuleWithContentBuilder<T> builder(HttpMethod... methods) {
        return builder(ImmutableSet.copyOf(requireNonNull(methods, "methods")));
    }

    /**
     * Returns a newly created {@link RetryRuleWithContentBuilder} with the specified {@link HttpMethod}s.
     */
    static <T extends Response> RetryRuleWithContentBuilder<T> builder(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!Iterables.isEmpty(methods), "methods can't be empty.");
        final ImmutableSet<HttpMethod> httpMethods = Sets.immutableEnumSet(methods);
        return builder((ctx, headers) -> httpMethods.contains(headers.method()));
    }

    /**
     * Returns a newly created {@link RetryRuleWithContentBuilder} with the specified
     * {@code requestHeadersFilter}.
     */
    static <T extends Response> RetryRuleWithContentBuilder<T> builder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        requireNonNull(requestHeadersFilter, "requestHeadersFilter");
        return new RetryRuleWithContentBuilder<>(requestHeadersFilter);
    }

    /**
     * Returns a {@link RetryRuleWithContent} that combines the specified {@code retryRules}.
     */
    @SafeVarargs
    static <T extends Response> RetryRuleWithContent<T> of(RetryRuleWithContent<T>... retryRules) {
        requireNonNull(retryRules, "retryRules");
        checkArgument(retryRules.length > 0, "retryRules can't be empty.");
        if (retryRules.length == 1) {
            return retryRules[0];
        }
        return of(ImmutableList.copyOf(retryRules));
    }

    /**
     * Returns a {@link RetryRuleWithContent} that combines all the {@link RetryRuleWithContent} of
     * the {@code retryRules}.
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    static <T extends Response> RetryRuleWithContent<T> of(
            Iterable<? extends RetryRuleWithContent<T>> retryRules) {
        requireNonNull(retryRules, "retryRules");
        checkArgument(!Iterables.isEmpty(retryRules), "retryRules should not be empty.");
        if (Iterables.size(retryRules) == 1) {
            return Iterables.get(retryRules, 0);
        }

        @SuppressWarnings("unchecked")
        final Iterable<RetryRuleWithContent<T>> cast = (Iterable<RetryRuleWithContent<T>>) retryRules;
        return Streams.stream(cast).reduce(RetryRuleWithContent::orElse).get();
    }

    /**
     * Returns a composed {@link RetryRuleWithContent} that represents a logical OR of this
     * {@link RetryRuleWithContent} and the specified {@link RetryRule}.
     * If this {@link RetryRuleWithContent} completes with {@link RetryDecision#next()},
     * then other {@link RetryRule} is evaluated.
     */
    default RetryRuleWithContent<T> orElse(RetryRule other) {
        requireNonNull(other, "other");
        return RetryRuleUtil.orElse(this, other);
    }

    /**
     * Returns a composed {@link RetryRuleWithContent} that represents a logical OR of this
     * {@link RetryRuleWithContent} and another.
     * If this {@link RetryRuleWithContent} completes with {@link RetryDecision#next()},
     * then other {@link RetryRuleWithContent} is evaluated.
     */
    default RetryRuleWithContent<T> orElse(RetryRuleWithContent<T> other) {
        requireNonNull(other, "other");
        return RetryRuleUtil.orElse(this, other);
    }

    /**
     * Tells whether the request sent with the specified {@link ClientRequestContext} requires a retry or not.
     * Implement this method to return a {@link CompletionStage} and to complete it with a desired
     * {@link Backoff}. To stop trying further, complete it with {@code null}.
     *
     * @param ctx the {@link ClientRequestContext} of this request.
     * @param response the {@link Response} from the server. {@code null} if a {@link Throwable} is raised
     *                 before receiving the content of the {@link Response}.
     * @param cause the {@link Throwable} which is raised while sending a request and before receiving
     *              the content of the {@link Response}. {@code null} if there's no exception.
     */
    CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx, @Nullable T response,
                                               @Nullable Throwable cause);

    /**
     * Returns whether this rule requires the response trailers to determine if a {@link Response} is
     * successful or not.
     */
    default boolean requiresResponseTrailers() {
        return false;
    }
}
