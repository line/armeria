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
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;

/**
 * Determines whether a {@link Response} should be reported as a success or failure to a
 * {@link CircuitBreaker} using the content of the {@link Response}. If you just need the HTTP headers
 * to make a decision, use {@link CircuitBreakerRule} for efficiency.
 *
 * @param <T> the response type
 */
@FunctionalInterface
public interface CircuitBreakerRuleWithContent<T extends Response> {

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContent} that will report a {@link Response} as
     * a failure if the specified {@code responseFilter} completes with {@code true}.
     */
    static <T extends Response> CircuitBreakerRuleWithContent<T> onResponse(
            BiFunction<? super ClientRequestContext, ? super T,
                    ? extends CompletionStage<Boolean>> responseFilter) {
        return CircuitBreakerRuleWithContent.<T>builder().onResponse(responseFilter).thenFailure();
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContentBuilder}.
     */
    static <T extends Response> CircuitBreakerRuleWithContentBuilder<T> builder() {
        return builder(HttpMethod.knownMethods());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContentBuilder} with the specified
     * {@link HttpMethod}s.
     */
    static <T extends Response> CircuitBreakerRuleWithContentBuilder<T> builder(HttpMethod... methods) {
        return builder(ImmutableSet.copyOf(requireNonNull(methods, "methods")));
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContentBuilder} with the specified
     * {@link HttpMethod}s.
     */
    static <T extends Response> CircuitBreakerRuleWithContentBuilder<T> builder(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!Iterables.isEmpty(methods), "methods can't be empty.");
        final ImmutableSet<HttpMethod> httpMethods = Sets.immutableEnumSet(methods);
        return builder((unused, headers) -> httpMethods.contains(headers.method()));
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContentBuilder} with the specified
     * {@code requestHeadersFilter}.
     */
    static <T extends Response> CircuitBreakerRuleWithContentBuilder<T> builder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        requireNonNull(requestHeadersFilter, "requestHeadersFilter");
        return new CircuitBreakerRuleWithContentBuilder<>(requestHeadersFilter);
    }

    /**
     * Returns a {@link CircuitBreakerRuleWithContent} that combines the specified
     * {@link CircuitBreakerRuleWithContent}s.
     */
    @SafeVarargs
    static <T extends Response> CircuitBreakerRuleWithContent<T> of(
            CircuitBreakerRuleWithContent<T>... circuitBreakerRules) {
        requireNonNull(circuitBreakerRules, "circuitBreakerRules");
        checkArgument(circuitBreakerRules.length > 0, "circuitBreakerRules can't be empty.");
        if (circuitBreakerRules.length == 1) {
            return circuitBreakerRules[0];
        }
        return of(ImmutableList.copyOf(circuitBreakerRules));
    }

    /**
     * Returns a {@link CircuitBreakerRuleWithContent} that combines the specified
     * {@link CircuitBreakerRuleWithContent}s.
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    static <T extends Response> CircuitBreakerRuleWithContent<T> of(
            Iterable<? extends CircuitBreakerRuleWithContent<T>> circuitBreakerRules) {
        requireNonNull(circuitBreakerRules, "circuitBreakerRules");
        checkArgument(!Iterables.isEmpty(circuitBreakerRules), "circuitBreakerRules should not be empty.");
        if (Iterables.size(circuitBreakerRules) == 1) {
            return Iterables.get(circuitBreakerRules, 0);
        }

        @SuppressWarnings("unchecked")
        final Iterable<CircuitBreakerRuleWithContent<T>> cast =
                (Iterable<CircuitBreakerRuleWithContent<T>>) circuitBreakerRules;
        return Streams.stream(cast).reduce(CircuitBreakerRuleWithContent::orElse).get();
    }

    /**
     * Returns a composed {@link CircuitBreakerRuleWithContent} that represents a logical OR of this
     * {@link CircuitBreakerRuleWithContent} and the specified {@link CircuitBreakerRule}.
     * If this {@link CircuitBreakerRuleWithContent} completes with {@link CircuitBreakerDecision#next()},
     * then other {@link CircuitBreakerRule} is evaluated.
     */
    default CircuitBreakerRuleWithContent<T> orElse(CircuitBreakerRule other) {
        requireNonNull(other, "other");
        return CircuitBreakerRuleUtil.orElse(this, other);
    }

    /**
     * Returns a composed {@link CircuitBreakerRuleWithContent} that represents a logical OR of this
     * {@link CircuitBreakerRuleWithContent} and another.
     * If this {@link CircuitBreakerRuleWithContent} completes with {@link CircuitBreakerDecision#next()},
     * then other {@link CircuitBreakerRuleWithContent} is evaluated.
     */
    default CircuitBreakerRuleWithContent<T> orElse(CircuitBreakerRuleWithContent<T> other) {
        requireNonNull(other, "other");
        return CircuitBreakerRuleUtil.orElse(this, other);
    }

    /**
     * Returns a {@link CompletionStage} that contains a {@link CircuitBreakerDecision}.
     * If {@link CircuitBreakerDecision#success()} is returned, {@link CircuitBreaker#onSuccess()} is called
     * so that the {@link CircuitBreaker} increases its success count and uses it to make a decision
     * to close or open the circuit. If {@link CircuitBreakerDecision#failure()} is returned, it works the other
     * way around. If {@link CircuitBreakerDecision#ignore()} is returned,
     * the {@link CircuitBreaker} ignores it.
     * If {@link CircuitBreakerDecision#next()} is returned, a next {@link CircuitBreakerRule} will
     * be evaluated.
     *
     * <p>Note that the last {@link CircuitBreakerRule} completes with {@link CircuitBreakerDecision#next()} or
     * a {@link Response} did not match the given {@link CircuitBreakerRule}s, the {@link Response} will be
     * reported as a success.
     *
     * @param ctx the {@link ClientRequestContext} of this request.
     * @param response the {@link Response} from the server. {@code null} if a {@link Throwable} is raised
     *                 before receiving the content of the {@link Response}.
     * @param cause the {@link Throwable} which is raised while sending a request and before receiving
     *              the content of the {@link Response}. {@code null} if there's no exception.
     */
    CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                  @Nullable T response,
                                                                  @Nullable Throwable cause);

    /**
     * Returns whether this rule requires the response trailers to determine if a {@link Response} is
     * successful or not.
     */
    default boolean requiresResponseTrailers() {
        return false;
    }
}
