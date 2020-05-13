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
import java.util.function.BiFunction;
import java.util.function.Predicate;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.client.AbstractRuleBuilder;
import com.linecorp.armeria.internal.client.AbstractRuleBuilderUtil;

/**
 * A builder class that creates {@link CircuitBreakerRule}.
 */
public final class CircuitBreakerRuleBuilder extends AbstractRuleBuilder {

    CircuitBreakerRuleBuilder(Predicate<? super RequestHeaders> requestHeadersFilter) {
        super(requestHeadersFilter);
    }

    /**
     * Returns newly created {@link CircuitBreakerRule} that determines whether a {@link Response} should be
     * reported as a success.
     */
    public CircuitBreakerRule thenSuccess() {
        return build(CircuitBreakerDecision.success());
    }

    /**
     * Returns newly created {@link CircuitBreakerRule} that determines whether a {@link Response} should be
     * reported as a failure.
     */
    public CircuitBreakerRule thenFailure() {
        return build(CircuitBreakerDecision.failure());
    }

    /**
     * Returns newly created {@link CircuitBreakerRule} that determines whether a {@link Response} should be
     * reported as a failure.
     */
    public CircuitBreakerRule thenIgnore() {
        return build(CircuitBreakerDecision.ignore());
    }

    private CircuitBreakerRule build(CircuitBreakerDecision decision) {
        return build(this, decision);
    }

    static CircuitBreakerRule build(AbstractRuleBuilder builder, CircuitBreakerDecision decision) {
        final BiFunction<? super ClientRequestContext, ? super Throwable, Boolean> filter =
                AbstractRuleBuilderUtil.buildFilter(builder);

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

        return filter.andThen(matched -> matched ? decisionFuture : NEXT_DECISION)::apply;
    }

    // Override the return type and Javadoc of chaining methods in superclass.

    /**
     * Adds the specified {@code responseHeadersFilter} for a {@link CircuitBreakerRule} which will
     * report a {@link Response} as a success, failure or ignore it according tothe build methods -
     * {@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}, if the specified
     * {@code responseHeadersFilter} completes with {@code true}.
     */
    @Override
    public CircuitBreakerRuleBuilder onResponseHeaders(
            Predicate<? super ResponseHeaders> responseHeadersFilter) {
        return (CircuitBreakerRuleBuilder) super.onResponseHeaders(responseHeadersFilter);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link CircuitBreakerRuleWithContent} which will
     * report a {@link Response} as a success, failure or ignore it according to the build methods -
     * {@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}, if the class of the response
     * status is one of the specified {@link HttpStatusClass}es.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return (CircuitBreakerRuleBuilder) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link CircuitBreakerRuleWithContent} which will
     * report a {@link Response} as a success, failure or ignore it according to the build methods -
     * {@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}, if the class of the response
     * status is one of the specified {@link HttpStatusClass}es.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return (CircuitBreakerRuleBuilder) super.onStatusClass(statusClasses);
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link CircuitBreakerRuleWithContent} which will
     * report a {@link Response} as a success, failure or ignore it according to the build methods -
     * {@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}, if the class of the response
     * status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    @Override
    public CircuitBreakerRuleBuilder onServerErrorStatus() {
        return (CircuitBreakerRuleBuilder) super.onServerErrorStatus();
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link CircuitBreakerRuleWithContent} which will report
     * a {@link Response} as a success, failure or ignore it according to the build methods -
     * {@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}, if the response status is
     * one of the specified {@link HttpStatus}es.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatus(HttpStatus... statuses) {
        return (CircuitBreakerRuleBuilder) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link CircuitBreakerRuleWithContent} which will report
     * a {@link Response} as a success, failure or ignore it according to the build methods -
     * {@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}, if the response status is
     * one of the specified {@link HttpStatus}es.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        return (CircuitBreakerRuleBuilder) super.onStatus(statuses);
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link CircuitBreakerRule} which will report
     * a {@link Response} as a success, failure or ignore it according to the build methods -
     * {@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}, if the response status matches
     * the specified {@code statusFilter}.
     */
    @Override
    public CircuitBreakerRuleBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        return (CircuitBreakerRuleBuilder) super.onStatus(statusFilter);
    }

    /**
     * Adds the specified exception type for a {@link CircuitBreakerRule} which will report a {@link Response}
     * as a success, failure or ignore it according to the build methods - {@link #thenSuccess()},
     * {@link #thenFailure()} and {@link #thenIgnore()}, if an {@link Exception} is
     * raised and that is instance of the specified {@code exception}.
     */
    @Override
    public CircuitBreakerRuleBuilder onException(Class<? extends Throwable> exception) {
        return (CircuitBreakerRuleBuilder) super.onException(exception);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link CircuitBreakerRule} which will report
     * a {@link Response} as a success, failure or ignore it according to the build methods -
     * {@link #thenSuccess()}, {@link #thenFailure()} and {@link #thenIgnore()}, if an {@link Exception} is
     * raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    @Override
    public CircuitBreakerRuleBuilder onException(Predicate<? super Throwable> exceptionFilter) {
        return (CircuitBreakerRuleBuilder) super.onException(exceptionFilter);
    }

    /**
     * Makes a {@link CircuitBreakerRuleWithContent} report a {@link Response} as a success, failure or
     * ignore it according to the build methods - {@link #thenSuccess()}, {@link #thenFailure()} and
     * {@link #thenIgnore()} if an {@link Exception} is raised.
     */
    @Override
    public CircuitBreakerRuleBuilder onException() {
        return (CircuitBreakerRuleBuilder) super.onException();
    }

    /**
     * Makes a {@link CircuitBreakerRule} report a {@link Response} as a success, failure or ignore it
     * according to the build methods - {@link #thenSuccess()}, {@link #thenFailure()} and
     * {@link #thenIgnore()}, if an {@link UnprocessedRequestException} which means that the request has not
     * been processed by the server is raised.
     */
    @Override
    public CircuitBreakerRuleBuilder onUnprocessed() {
        return (CircuitBreakerRuleBuilder) super.onUnprocessed();
    }
}
