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
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Determines whether a failed request should be retried.
 * If you need to determine by looking into the {@link Response}, use {@link RetryRuleWithContent}.
 */
@FunctionalInterface
public interface RetryRule {

    /**
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@link Backoff#ofDefault() default backoff} if the request HTTP method is
     * <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotent</a>
     * and an {@link Exception} is raised or the class of the response status is
     * {@link HttpStatusClass#SERVER_ERROR}.
     * Otherwise, an {@link UnprocessedRequestException} is raised regardless of the request HTTP method.
     *
     * <p>Note that a client can safely retry a failed request with this rule if an endpoint service produces
     * the same result (no side effects) on idempotent HTTP methods or {@link UnprocessedRequestException}.
     *
     * <p>This method is a shortcut for:
     * <pre>{@code
     * RetryRule.of(RetryRule.builder(HttpMethods.idempotentMethods())
     *                       .onServerErrorStatus()
     *                       .onUnprocessed()
     *                       .thenBackoff(),
     *              RetryRule.onUnprocessed());
     * }</pre>
     */
    static RetryRule failsafe() {
        return failsafe(Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the specified {@link Backoff} if the
     * request HTTP method is
     * <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotent</a>
     * and an {@link Exception} is raised or the class of the response status is
     * {@link HttpStatusClass#SERVER_ERROR}.
     * Otherwise, an {@link UnprocessedRequestException} is raised regardless of the request HTTP method.
     *
     * <p>Note that a client can safely retry a failed request with this rule if an endpoint service produces
     * the same result (no side effects) on idempotent HTTP methods or {@link UnprocessedRequestException}.
     *
     * <p>This method is a shortcut for:
     * <pre>{@code
     * RetryRule.of(RetryRule.builder(HttpMethods.idempotentMethods())
     *                       .onServerErrorStatus()
     *                       .onUnprocessed()
     *                       .thenBackoff(backoff),
     *              RetryRule.onUnprocessed(backoff));
     * }</pre>
     */
    static RetryRule failsafe(Backoff backoff) {
        return of(builder(HttpMethod.idempotentMethods()).onServerErrorStatus()
                                                         .onException()
                                                         .thenBackoff(backoff),
                  onUnprocessed(backoff));
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with
     * the {@linkplain Backoff#ofDefault() default backoff} if the class of the response status is
     * the specified {@link HttpStatusClass}.
     */
    static RetryRule onStatusClass(HttpStatusClass statusClass) {
        return onStatusClass(statusClass, Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the specified {@link Backoff} if the
     * class of the response status is the specified {@link HttpStatusClass}.
     */
    static RetryRule onStatusClass(HttpStatusClass statusClass, Backoff backoff) {
        return builder().onStatusClass(statusClass).thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with
     * the {@linkplain Backoff#ofDefault() default backoff} if the class of the response status is
     * the specified {@link HttpStatusClass}es.
     */
    static RetryRule onStatusClass(HttpStatusClass... statusClasses) {
        return builder().onStatusClass(statusClasses).thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with
     * the {@linkplain Backoff#ofDefault() default backoff} if the class of the response status is
     * one of the specified {@link HttpStatusClass}es.
     */
    static RetryRule onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return onStatusClass(statusClasses, Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the specified {@link Backoff} if the
     * class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    static RetryRule onStatusClass(Iterable<HttpStatusClass> statusClasses, Backoff backoff) {
        return builder().onStatusClass(statusClasses).thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff}
     * if the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    static RetryRule onServerErrorStatus() {
        return onServerErrorStatus(Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the specified {@link Backoff} if the
     * class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    static RetryRule onServerErrorStatus(Backoff backoff) {
        return builder().onServerErrorStatus().thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the response status is one of
     * the specified {@link HttpStatus}es.
     */
    static RetryRule onStatus(HttpStatus... statuses) {
        return builder().onStatus(statuses).thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the response status is one of
     * the specified {@link HttpStatus}es.
     */
    static RetryRule onStatus(Iterable<HttpStatus> statuses) {
        return onStatus(statuses, Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the specified {@link Backoff} if the
     * response status is one of the specified {@link HttpStatus}es.
     */
    static RetryRule onStatus(Iterable<HttpStatus> statuses, Backoff backoff) {
        return builder().onStatus(statuses).thenBackoff(backoff);
    }

    /**
     * Returns a newly created a {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the response status matches
     * the specified {@code statusFilter}.
     */
    static RetryRule onStatus(BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter) {
        return onStatus(statusFilter, Backoff.ofDefault());
    }

    /**
     * Returns a newly created a {@link RetryRule} that will retry with the specified {@link Backoff} if the
     * response status matches the specified {@code statusFilter}.
     */
    static RetryRule onStatus(BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter,
                              Backoff backoff) {
        return builder().onStatus(statusFilter).thenBackoff(backoff);
    }

    /**
     * Returns a newly created a {@link RetryRule} that will retry with
     * the {@linkplain Backoff#ofDefault() default backoff} if an {@link Exception} is raised and
     * that is an instance of the specified {@code exception}.
     */
    static RetryRule onException(Class<? extends Throwable> exception) {
        return onException(exception, Backoff.ofDefault());
    }

    /**
     * Returns a newly created a {@link RetryRule} that will retry with the specified {@link Backoff} if an
     * {@link Exception} is raised and that is an instance of the specified {@code exception}.
     */
    static RetryRule onException(Class<? extends Throwable> exception, Backoff backoff) {
        return builder().onException(exception).thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if an {@link Exception} is raised and
     * the specified {@code exceptionFilter} returns {@code true}.
     */
    static RetryRule onException(BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter) {
        return onException(exceptionFilter, Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the specified {@link Backoff} if an
     * {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    static RetryRule onException(BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter,
                                 Backoff backoff) {
        return builder().onException(exceptionFilter).thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRule} that retries with
     * {@linkplain Backoff#ofDefault() default backoff} on any {@link Exception}.
     * Note that this rule should be used carefully because it reties regardless of
     * <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotency</a>.
     */
    static RetryRule onException() {
        return onException(Backoff.ofDefault());
    }

    /**
     * Returns a newly created {@link RetryRule} that retries with the specified {@link Backoff} on any
     * {@link Exception}.
     * Note that this rule should be used carefully because it reties regardless of
     * <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotency</a>.
     */
    static RetryRule onException(Backoff backoff) {
        return builder().onException().thenBackoff(backoff);
    }

    /**
     * Returns a {@link RetryRule} that retries with the {@linkplain Backoff#ofDefault() default backoff}
     * on an {@link UnprocessedRequestException} which means that the request has not been processed by
     * the server. Therefore, you can safely retry the request without worrying about the idempotency of
     * the request.
     */
    static RetryRule onUnprocessed() {
        return onUnprocessed(Backoff.ofDefault());
    }

    /**
     * Returns a {@link RetryRule} that retries with the specified {@link Backoff} on an
     * {@link UnprocessedRequestException} which means that the request has not been processed by the server.
     * Therefore, you can safely retry the request without worrying about the idempotency of the request.
     */
    static RetryRule onUnprocessed(Backoff backoff) {
        return builder().onUnprocessed().thenBackoff(backoff);
    }

    /**
     * Returns a newly created {@link RetryRuleBuilder}.
     */
    static RetryRuleBuilder builder() {
        return builder(HttpMethod.knownMethods());
    }

    /**
     * Returns a newly created {@link RetryRuleBuilder} with the specified {@link HttpMethod}s.
     */
    static RetryRuleBuilder builder(HttpMethod... methods) {
        requireNonNull(methods, "methods");
        return builder(ImmutableSet.copyOf(methods));
    }

    /**
     * Returns a newly created {@link RetryRuleBuilder} with the specified {@link HttpMethod}s.
     */
    static RetryRuleBuilder builder(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!Iterables.isEmpty(methods), "method can't be empty.");
        final ImmutableSet<HttpMethod> httpMethods = Sets.immutableEnumSet(methods);
        return builder((ctx, headers) -> httpMethods.contains(headers.method()));
    }

    /**
     * Returns a newly created {@link RetryRuleBuilder} with the specified {@code requestHeadersFilter}.
     */
    static RetryRuleBuilder builder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        return new RetryRuleBuilder(requireNonNull(requestHeadersFilter, "requestHeadersFilter"));
    }

    /**
     * Returns a {@link RetryRule} that combines the specified {@code retryRules}.
     */
    static RetryRule of(RetryRule... retryRules) {
        requireNonNull(retryRules, "retryRules");
        checkArgument(retryRules.length > 0, "retryRules can't be empty.");
        if (retryRules.length == 1) {
            return retryRules[0];
        }
        return of(ImmutableList.copyOf(retryRules));
    }

    /**
     * Returns a {@link RetryRule} that combines all the {@link RetryRule} of
     * the {@code retryRules}.
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    static RetryRule of(Iterable<? extends RetryRule> retryRules) {
        requireNonNull(retryRules, "retryRules");
        checkArgument(!Iterables.isEmpty(retryRules), "retryRules can't be empty.");
        if (Iterables.size(retryRules) == 1) {
            return Iterables.get(retryRules, 0);
        }

        @SuppressWarnings("unchecked")
        final Iterable<RetryRule> cast = (Iterable<RetryRule>) retryRules;
        return Streams.stream(cast).reduce(RetryRule::orElse).get();
    }

    /**
     * Returns a composed {@link RetryRule} that represents a logical OR of this {@link RetryRule} and another.
     * If this {@link RetryRule} completes with {@link RetryDecision#next()}, then other {@link RetryRule}
     * is evaluated.
     */
    default RetryRule orElse(RetryRule other) {
        return RetryRuleUtil.orElse(this, requireNonNull(other, "other"));
    }

    /**
     * Tells whether the request sent with the specified {@link ClientRequestContext} requires a retry or not.
     * Implement this method to return a {@link CompletionStage} and to complete it with a desired
     * {@link RetryDecision#retry(Backoff)}.
     * To not retry, complete it with {@link RetryDecision#noRetry()}.
     * To skip this {@link RetryRule} and find other {@link RetryRule}, complete it with
     * {@link RetryDecision#next()}.
     * If the return value of the last {@link RetryRule} completes with {@link RetryDecision#next()},
     * the request never retries.
     *
     * <p>To retrieve the {@link ResponseHeaders}, you can use the specified {@link ClientRequestContext}:
     * <pre>{@code
     * CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
     *     if (cause != null) {
     *         return UnmodifiableFuture.completedFuture(RetryDecision.retry(backoff));
     *     }
     *
     *     ResponseHeaders responseHeaders = ctx.log().ensureAvailable(RequestLogProperty.RESPONSE_HEADERS)
     *                                          .responseHeaders();
     *     if (responseHeaders.status().codeClass() == HttpStatusClass.SERVER_ERROR) {
     *         return UnmodifiableFuture.completedFuture(RetryDecision.retry(backoff));
     *     }
     *     if (responseHeaders.status() == HttpStatus.TOO_MANY_REQUESTS) {
     *         return UnmodifiableFuture.completedFuture(RetryDecision.noRetry());
     *     }
     *
     *     return UnmodifiableFuture.completedFuture(RetryDecision.next());
     * }
     * }</pre>
     *
     * @param ctx the {@link ClientRequestContext} of this request
     * @param cause the {@link Throwable} which is raised while sending a request. {@code null} if there's no
     *              exception.
     */
    CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause);

    /**
     * Returns whether this rule requires the response trailers to determine if a {@link Response} is
     * successful or not.
     */
    default boolean requiresResponseTrailers() {
        return false;
    }
}
