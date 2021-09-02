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

import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.FAILURE_DECISION;
import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.IGNORE_DECISION;
import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.NEXT_DECISION;
import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleUtil.SUCCESS_DECISION;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.AbstractRuleBuilder;
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
 * A builder for creating a new {@link CircuitBreakerRule}.
 */
public final class CircuitBreakerRuleBuilder extends AbstractRuleBuilder {

    CircuitBreakerRuleBuilder(
            BiPredicate<? super ClientRequestContext, ? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that determines a {@link Response} as a success when
     * the rule matches.
     */
    public CircuitBreakerRule thenSuccess() {
        return build(CircuitBreakerDecision.success());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that determines a {@link Response} as a failure when
     * the rule matches.
     */
    public CircuitBreakerRule thenFailure() {
        return build(CircuitBreakerDecision.failure());
    }

    /**
     * Returns a newly created {@link CircuitBreakerRule} that ignores a {@link Response} when the rule matches.
     */
    public CircuitBreakerRule thenIgnore() {
        return build(CircuitBreakerDecision.ignore());
    }

    private CircuitBreakerRule build(CircuitBreakerDecision decision) {
        final BiFunction<? super ClientRequestContext, ? super @Nullable Throwable, Boolean> ruleFilter =
                AbstractRuleBuilderUtil.buildFilter(requestHeadersFilter(), responseHeadersFilter(),
                                                    responseTrailersFilter(), exceptionFilter(), false);
        return build(ruleFilter, decision, responseTrailersFilter() != null);
    }

    static CircuitBreakerRule build(
            BiFunction<? super ClientRequestContext, ? super @Nullable Throwable, Boolean> ruleFilter,
            CircuitBreakerDecision decision, boolean requiresResponseTrailers) {
        final CompletableFuture<CircuitBreakerDecision> decisionFuture;
        if (decision == CircuitBreakerDecision.success()) {
            decisionFuture = SUCCESS_DECISION;
        } else if (decision == CircuitBreakerDecision.failure()) {
            decisionFuture = FAILURE_DECISION;
        } else if (decision == CircuitBreakerDecision.ignore()) {
            decisionFuture = IGNORE_DECISION;
        } else {
            decisionFuture = NEXT_DECISION;
        }

        return new CircuitBreakerRule() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                                 @Nullable Throwable cause) {
                return ruleFilter.apply(ctx, cause) ? decisionFuture : NEXT_DECISION;
            }

            @Override
            public boolean requiresResponseTrailers() {
                return requiresResponseTrailers;
            }
        };
    }

    // Override the return type and Javadoc of chaining methods in superclass.
    /**
     * Adds the specified {@code responseHeadersFilter} for a {@link CircuitBreakerRule}.
     * If the specified {@code responseHeadersFilter} returns {@code true},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onResponseHeaders(
            BiPredicate<? super ClientRequestContext, ? super ResponseHeaders> responseHeadersFilter) {
        return (CircuitBreakerRuleBuilder) super.onResponseHeaders(responseHeadersFilter);
    }

    /**
     * Adds the specified {@code responseTrailersFilter} for a {@link CircuitBreakerRule}.
     * If the specified {@code responseTrailersFilter} returns {@code true},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onResponseTrailers(
            BiPredicate<? super ClientRequestContext, ? super HttpHeaders> responseTrailersFilter) {
        return (CircuitBreakerRuleBuilder) super.onResponseTrailers(responseTrailersFilter);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link CircuitBreakerRule}.
     * If the class of the response status is one of the specified {@link HttpStatusClass}es,
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return (CircuitBreakerRuleBuilder) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link CircuitBreakerRule}.
     * If the class of the response status is one of the specified {@link HttpStatusClass}es,
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return (CircuitBreakerRuleBuilder) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link CircuitBreakerRule}.
     * If the class of the response status is {@link HttpStatusClass#SERVER_ERROR},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onServerErrorStatus() {
        return (CircuitBreakerRuleBuilder) super.onServerErrorStatus();
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link CircuitBreakerRule}.
     * If the response status is one of the specified {@link HttpStatus}es,
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatus(HttpStatus... statuses) {
        return (CircuitBreakerRuleBuilder) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link CircuitBreakerRule}.
     * If the response status is one of the specified {@link HttpStatus}es,
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        return (CircuitBreakerRuleBuilder) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link CircuitBreakerRule}.
     * If the response status matches the specified {@code statusFilter},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatus(
            BiPredicate<? super ClientRequestContext, ? super HttpStatus> statusFilter) {
        return (CircuitBreakerRuleBuilder) super.onStatus(statusFilter);
    }

    /**
     * Adds the specified exception type for a {@link CircuitBreakerRule}.
     * If an {@link Exception} is raised and it is an instance of the specified {@code exception},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onException(Class<? extends Throwable> exception) {
        return (CircuitBreakerRuleBuilder) super.onException(exception);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link CircuitBreakerRule}.
     * If an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true},
     * depending on the build methods({@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}),
     * a {@link Response} is reported as a success or failure to a {@link CircuitBreaker} or ignored.
     */
    @Override
    public CircuitBreakerRuleBuilder onException(
            BiPredicate<? super ClientRequestContext, ? super Throwable> exceptionFilter) {
        return (CircuitBreakerRuleBuilder) super.onException(exceptionFilter);
    }

    /**
     * Reports a {@link Response} as a success or failure to a {@link CircuitBreaker},
     * or ignores it according to the build methods({@link #thenSuccess()}, {@link #thenFailure()}
     * and {@link #thenIgnore()}), if an {@link Exception} is raised.
     */
    @Override
    public CircuitBreakerRuleBuilder onException() {
        return (CircuitBreakerRuleBuilder) super.onException();
    }

    /**
     * Reports a {@link Response} as a success or failure to a {@link CircuitBreaker},
     * or ignores it according to the build methods({@link #thenSuccess()}, {@link #thenFailure()} and
     * {@link #thenIgnore()}), if an {@link UnprocessedRequestException}, which means that the request has not
     * been processed by the server, is raised.
     */
    @Override
    public CircuitBreakerRuleBuilder onUnprocessed() {
        return (CircuitBreakerRuleBuilder) super.onUnprocessed();
    }
}
