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

package com.linecorp.armeria.client.circuitbreaker;

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
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Determines whether a {@link Response} should be reported as a success or failure to a
 * {@link CircuitBreaker}. If you need to determine whether the request was successful by looking into the
 * {@link Response} content, use {@link CircuitBreakerRuleWithContent}.
 *
 * <p>Note that if the last {@link CircuitBreakerRule} completes with {@link CircuitBreakerDecision#next()} or
 * a {@link Response} is not matched with the {@link CircuitBreakerRule}s, then the {@link Response} will be
 * reported as a success.
 *
 * <p>For example:
 * <pre>{@code
 * // If a response status is 500(Interval Server Error), the response will be reported as a failure.
 * // Otherwise, the response will be reported as a success.
 * CircuitBreakerRule.onStatus(HttpStatus.INTERNAL_SERVER_ERROR);
 *
 * // A response will be reported as a success if no exception is raised.
 * CircuitBreakerRule.onException();
 *
 * // A CircuitBreakerRule that reports a response as a failure except that a response status code is 2xx.
 * CircuitBreakerRule.of(
 *         // Report as a success if the class of a response status is 2xx
 *         CircuitBreakerRule.builder()
 *                           .onStatusClass(HttpStatusClass.SUCCESS)
 *                           .thenSuccess(),
 *         // Everything else is reported as a failure
 *         ClientBreakerRule.builder().thenFailure());
 * }</pre>
 */
@FunctionalInterface
public interface CircuitBreakerRule {

    /**
     * Returns a newly created {@link CircuitBreakerRule} that will report a {@link Response} as a failure,
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    static CircuitBreakerRule onStatusClass(HttpStatusClass statusClass) {
        return builder().onStatusClass(statusClass).thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that will report a {@link Response} as a failure,
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    static CircuitBreakerRule onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return builder().onStatusClass(statusClasses).thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that will report a {@link Response} as a failure,
     * if the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    static CircuitBreakerRule onServerErrorStatus() {
        return builder().onServerErrorStatus().thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that will report a {@link Response} as a failure,
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    static CircuitBreakerRule onStatus(HttpStatus... statuses) {
        return builder().onStatus(statuses).thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that will report a {@link Response} as a failure,
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    static CircuitBreakerRule onStatus(Iterable<HttpStatus> statuses) {
        return builder().onStatus(statuses).thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that will report a {@link Response} as a failure,
     * if the specified {@code statusFilter} returns {@code true}.
     */
    static CircuitBreakerRule onStatus(
            BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter) {
        return builder().onStatus(statusFilter).thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that will report a {@link Response} as a failure,
     * if an {@link Exception} is raised and it is an instance of the specified {@code exception}.
     */
    static CircuitBreakerRule onException(Class<? extends Throwable> exception) {
        return builder().onException(exception).thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that will report a {@link Response} as a failure,
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    static CircuitBreakerRule onException(
            BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter) {
        return builder().onException(exceptionFilter).thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that will report a {@link Response} as a failure,
     * if an {@link Exception} is raised.
     */
    static CircuitBreakerRule onException() {
        return builder().onException().thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleBuilder}.
     */
    static CircuitBreakerRuleBuilder builder() {
        return builder(HttpMethod.knownMethods());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleBuilder} with the specified {@link HttpMethod}s.
     */
    static CircuitBreakerRuleBuilder builder(HttpMethod... methods) {
        requireNonNull(methods, "methods");
        return builder(ImmutableSet.copyOf(methods));
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleBuilder} with the specified {@link HttpMethod}s.
     */
    static CircuitBreakerRuleBuilder builder(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!Iterables.isEmpty(methods), "method can't be empty.");
        final ImmutableSet<HttpMethod> httpMethods = Sets.immutableEnumSet(methods);
        return builder((unused, headers) -> httpMethods.contains(headers.method()));
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleBuilder} with the specified
     * {@code requestHeadersFilter}.
     */
    static CircuitBreakerRuleBuilder builder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        return new CircuitBreakerRuleBuilder(requireNonNull(requestHeadersFilter, "requestHeadersFilter"));
    }

    /**
     * Returns a {@link CircuitBreakerRule} that combines the specified {@link CircuitBreakerRule}s.
     */
    static CircuitBreakerRule of(CircuitBreakerRule... circuitBreakerRules) {
        requireNonNull(circuitBreakerRules, "circuitBreakerRules");
        checkArgument(circuitBreakerRules.length > 0, "circuitBreakerRules can't be empty.");
        if (circuitBreakerRules.length == 1) {
            return circuitBreakerRules[0];
        }
        return of(ImmutableList.copyOf(circuitBreakerRules));
    }

    /**
     * Returns a {@link CircuitBreakerRule} that combines the specified {@link CircuitBreakerRule}s.
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    static CircuitBreakerRule of(Iterable<? extends CircuitBreakerRule> circuitBreakerRules) {
        requireNonNull(circuitBreakerRules, "circuitBreakerRules");
        checkArgument(!Iterables.isEmpty(circuitBreakerRules), "circuitBreakerRules can't be empty.");
        if (Iterables.size(circuitBreakerRules) == 1) {
            return Iterables.get(circuitBreakerRules, 0);
        }

        @SuppressWarnings("unchecked")
        final Iterable<CircuitBreakerRule> cast = (Iterable<CircuitBreakerRule>) circuitBreakerRules;
        return Streams.stream(cast).reduce(CircuitBreakerRule::orElse).get();
    }

    /**
     * Returns a composed {@link CircuitBreakerRule} that represents a logical OR of
     * this {@link CircuitBreakerRule} and another. If this {@link CircuitBreakerRule} completes with
     * {@link CircuitBreakerDecision#next()}, then other {@link CircuitBreakerRule} is evaluated.
     */
    default CircuitBreakerRule orElse(CircuitBreakerRule other) {
        return CircuitBreakerRuleUtil.orElse(this, requireNonNull(other, "other"));
    }

    /**
     * Returns a {@link CompletionStage} that contains a {@link CircuitBreakerDecision} which indicates
     * a {@link Response} is successful or not. If {@link CircuitBreakerDecision#success()} is returned,
     * {@link CircuitBreaker#onSuccess()} is called so that the {@link CircuitBreaker} increases its success
     * count and uses it to make a decision to close or open the circuit.
     * If {@link CircuitBreakerDecision#failure()} is returned, it works
     * the other way around. If {@link CircuitBreakerDecision#ignore()} is returned, the {@link CircuitBreaker}
     * ignores it. If {@link CircuitBreakerDecision#next()} is returned, a next {@link CircuitBreakerRule} will
     * be evaluated.
     *
     * <p>Note that if the last {@link CircuitBreakerRule} completes with {@link CircuitBreakerDecision#next()}
     * or a {@link Response} is not matched with the given {@link CircuitBreakerRule}s,
     * then the {@link Response} is reported as a success.
     *
     * <p>To retrieve the {@link ResponseHeaders}, you can use the specified {@link ClientRequestContext}:
     * <pre>{@code
     * > CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
     * >                                                               @Nullable Throwable cause) {
     * >     if (cause != null) {
     * >         return CompletableFuture.completedFuture(CircuitBreakerDecision.failure());
     * >     }
     *
     * >     ResponseHeaders responseHeaders = ctx.log().responseHeaders();
     * >     if (responseHeaders.status().codeClass() == HttpStatusClass.SERVER_ERROR) {
     * >         return CompletableFuture.completedFuture(CircuitBreakerDecision.failure());
     * >     }
     * >     ...
     * >     return CompletableFuture.completedFuture(CircuitBreakerDecision.success())
     * > }
     * }</pre>
     *
     * @param ctx the {@link ClientRequestContext} of this request
     * @param cause the {@link Throwable} which is raised while sending a request. {@code null} if there's no
     *              exception.
     */
    CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                  @Nullable Throwable cause);

    /**
     * Returns whether this rule requires the response trailers to determine if a {@link Response} is
     * successful or not.
     */
    default boolean requiresResponseTrailers() {
        return false;
    }
}
