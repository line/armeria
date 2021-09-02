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

import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.NEXT_DECISION;

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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.AbstractRuleBuilderUtil;

/**
 * A builder for creating a new {@link CircuitBreakerRuleWithContent}.
 * @param <T> the response type
 */
public final class CircuitBreakerRuleWithContentBuilder<T extends Response>
        extends AbstractRuleWithContentBuilder<T> {

    CircuitBreakerRuleWithContentBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContent} that determines a {@link Response} as
     * a success when the rule matches.
     */
    public CircuitBreakerRuleWithContent<T> thenSuccess() {
        return build(CircuitBreakerDecision.success());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContent} that determines a {@link Response} as
     * a failure when the rule matches.
     */
    public CircuitBreakerRuleWithContent<T> thenFailure() {
        return build(CircuitBreakerDecision.failure());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRuleWithContent} that ignores a {@link Response} when
     * the rule matches.
     */
    public CircuitBreakerRuleWithContent<T> thenIgnore() {
        return build(CircuitBreakerDecision.ignore());
    }

    private CircuitBreakerRuleWithContent<T> build(CircuitBreakerDecision decision) {
        @Nullable final BiFunction<? super ClientRequestContext, ? super T,
                ? extends CompletionStage<Boolean>> responseFilter = responseFilter();
        final boolean hasResponseFilter = responseFilter != null;
        final BiFunction<? super ClientRequestContext, ? super @Nullable Throwable, Boolean> ruleFilter =
                AbstractRuleBuilderUtil.buildFilter(requestHeadersFilter(), responseHeadersFilter(),
                                                    responseTrailersFilter(), exceptionFilter(),
                                                    hasResponseFilter);

        final CircuitBreakerRule first = CircuitBreakerRuleBuilder.build(ruleFilter, decision,
                                                                         responseTrailersFilter() != null);
        if (!hasResponseFilter) {
            return CircuitBreakerRuleUtil.fromCircuitBreakerRule(first);
        }

        final CircuitBreakerRuleWithContent<T> second = (ctx, content, cause) -> {
            if (content == null) {
                return NEXT_DECISION;
            }
            return responseFilter.apply(ctx, content)
                                 .handle((matched, cause0) -> {
                                     if (cause0 != null) {
                                         return CircuitBreakerDecision.next();
                                     }
                                     return matched ? decision : CircuitBreakerDecision.next();
                                 });
        };
        return CircuitBreakerRuleUtil.orElse(first, second);
    }

    // Override the return type and Javadoc of chaining methods in superclass.

    /**
     * Adds the specified {@code responseFilter} for a {@link CircuitBreakerRuleWithContent}.
     * If the specified {@code responseFilter} completes with {@code true},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onResponse(
            BiFunction<? super ClientRequestContext, ? super T,
                    ? extends CompletionStage<Boolean>> responseFilter) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onResponse(responseFilter);
    }

    /**
     * Adds the specified {@code responseHeadersFilter} for a {@link CircuitBreakerRuleWithContent}.
     * If the specified {@code responseHeadersFilter} returns {@code true},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onResponseHeaders(
            BiPredicate<? super ClientRequestContext, ? super ResponseHeaders> responseHeadersFilter) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onResponseHeaders(responseHeadersFilter);
    }

    /**
     * Adds the specified {@code responseTrailersFilter} for a {@link CircuitBreakerRuleWithContent}.
     * If the specified {@code responseTrailersFilter} returns {@code true},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onResponseTrailers(
            BiPredicate<? super ClientRequestContext, ? super HttpHeaders> responseTrailersFilter) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onResponseTrailers(responseTrailersFilter);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link CircuitBreakerRuleWithContent}.
     * If the class of the response status is one of the specified {@link HttpStatusClass}es,
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onStatusClass(HttpStatusClass... statusClasses) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link CircuitBreakerRuleWithContent}.
     * If the class of the response status is one of the specified {@link HttpStatusClass}es,
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link CircuitBreakerRuleWithContent}.
     * If the class of the response status is {@link HttpStatusClass#SERVER_ERROR},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onServerErrorStatus() {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onServerErrorStatus();
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link CircuitBreakerRuleWithContent}.
     * If the response status is one of the specified {@link HttpStatus}es,
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onStatus(HttpStatus... statuses) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link CircuitBreakerRuleWithContent}.
     * If the response status is one of the specified {@link HttpStatus}es,
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onStatus(Iterable<HttpStatus> statuses) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link CircuitBreakerRuleWithContent}.
     * If the response status matches the specified {@code statusFilter},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onStatus(
            BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onStatus(statusFilter);
    }

    /**
     * Adds the specified exception type for a {@link CircuitBreakerRuleWithContent}.
     * If an {@link Exception} is raised and it is an instance of the specified {@code exception},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onException(Class<? extends Throwable> exception) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onException(exception);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link CircuitBreakerRuleWithContent}.
     * If an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onException(
            BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter) {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onException(exceptionFilter);
    }

    /**
     * Reports a {@link Response} as a success or failure to a {@link CircuitBreaker},
     * or ignores it according to the build methods({@link #thenSuccess()}, {@link #thenFailure()} and
     * {@link #thenIgnore()}), if an {@link Exception} is raised.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onException() {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onException();
    }

    /**
     * Reports a {@link Response} as a success or failure to a {@link CircuitBreaker},
     * or ignores it according to the build methods({@link #thenSuccess()}, {@link #thenFailure()} and
     * {@link #thenIgnore()}), if an {@link UnprocessedRequestException}, which means that the request has not
     * been processed by the server, is raised.
     */
    @SuppressWarnings("unchecked")
    @Override
    public CircuitBreakerRuleWithContentBuilder<T> onUnprocessed() {
        return (CircuitBreakerRuleWithContentBuilder<T>) super.onUnprocessed();
    }
}
