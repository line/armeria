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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A builder which builds a {@link RetryStrategy}.
 */
public final class RetryStrategyBuilder extends AbstractRetryStrategyBindingBuilder {

    private static final CompletableFuture<Backoff> NULL_BACKOFF = CompletableFuture.completedFuture(null);
    private static final RetryStrategy[] EMPTY_RETRY_STRATEGIES = new RetryStrategy[0];

    private final ImmutableList.Builder<RetryStrategy> retryStrategiesBuilder = ImmutableList.builder();

    RetryStrategyBuilder() {}

    @Override
    public RetryStrategyBindingBuilder onIdempotentMethods() {
        return newBindingBuilder().onIdempotentMethods();
    }

    @Override
    public RetryStrategyBindingBuilder onMethods(HttpMethod... methods) {
        return newBindingBuilder().onMethods(methods);
    }

    @Override
    public RetryStrategyBindingBuilder onMethods(Iterable<HttpMethod> methods) {
        return newBindingBuilder().onMethods(methods);
    }

    @Override
    public RetryStrategyBindingBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return newBindingBuilder().onStatusClass(statusClasses);
    }

    @Override
    public RetryStrategyBindingBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return newBindingBuilder().onStatusClass(statusClasses);
    }

    @Override
    public RetryStrategyBindingBuilder onServerErrorStatus() {
        return newBindingBuilder().onServerErrorStatus();
    }

    @Override
    public RetryStrategyBindingBuilder onStatus(HttpStatus... statuses) {
        return newBindingBuilder().onStatus(statuses);
    }

    @Override
    public RetryStrategyBindingBuilder onStatus(Iterable<HttpStatus> statuses) {
        return newBindingBuilder().onStatus(statuses);
    }

    @Override
    public RetryStrategyBindingBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        return newBindingBuilder().onStatus(statusFilter);
    }

    @Override
    public RetryStrategyBindingBuilder onException(Class<? extends Throwable> exception) {
        return newBindingBuilder().onException(exception);
    }

    @Override
    public RetryStrategyBindingBuilder onException(Predicate<? super Throwable> exceptionFilter) {
        return newBindingBuilder().onException(exceptionFilter);
    }

    @Override
    public RetryStrategyBindingBuilder onException() {
        return newBindingBuilder().onException();
    }

    @Override
    public RetryStrategyBindingBuilder onUnProcessed() {
        return newBindingBuilder().onUnProcessed();
    }

    /**
     * Adds a {@link RetryStrategy}.
     */
    public RetryStrategyBuilder add(RetryStrategy retryStrategy) {
        retryStrategiesBuilder.add(requireNonNull(retryStrategy, "retryStrategy"));
        return this;
    }

    /**
     * Adds a {@link RetryRule}.
     *
     * <p><pre>{@code
     * RetryStrategy.builder()
     *              .on(RetryRule.onStatus(HttpStatus.SERVICE_UNAVAILABLE)
     *                           .onException(ex -> ex instanceof ClosedSessionException)
     *                           .onMethod(HttpMethod.GET)
     *                           .thenBackOff(backoffOn503))
     *              .build();
     * }</pre>
     */
    RetryStrategyBuilder on(RetryRule retryRule) {
        return add(build(requireNonNull(retryRule, "retryRule")));
    }

    /**
     * Returns a newly-created {@link RetryStrategy} based on the strategies set so far.
     */
    public RetryStrategy build() {
        final RetryStrategy[] retryStrategies = retryStrategiesBuilder.build().toArray(EMPTY_RETRY_STRATEGIES);
        checkState(retryStrategies.length > 0, "at least one retry strategy should be set");

        if (retryStrategies.length == 1) {
            return retryStrategies[0];
        }

        return (ctx, cause) -> {
            for (RetryStrategy retryStrategy : retryStrategies) {
                final CompletionStage<Backoff> backoff = retryStrategy.shouldRetry(ctx, cause);
                if (backoff != NULL_BACKOFF) {
                    return backoff;
                }
            }
            return NULL_BACKOFF;
        };
    }

    private static RetryStrategy build(RetryRule rule) {
        return (ctx, cause) -> {
            if (!rule.methods().contains(ctx.request().method())) {
                return NULL_BACKOFF;
            }

            final Backoff backoff = rule.backoff();
            final Predicate<Throwable> exceptionFilter = rule.exceptionFilter();
            if (cause != null && exceptionFilter != null && exceptionFilter.test(Exceptions.peel(cause))) {
                return CompletableFuture.completedFuture(backoff);
            }

            if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                final HttpStatus responseStatus = ctx.log().partial().responseHeaders().status();
                final Set<HttpStatusClass> statusClasses = rule.statusClasses();
                if (statusClasses != null && statusClasses.contains(responseStatus.codeClass())) {
                    return CompletableFuture.completedFuture(backoff);
                }

                final Set<HttpStatus> statuses = rule.statuses();
                final Predicate<HttpStatus> statusFilter = rule.statusFilter();
                if ((statuses != null && statuses.contains(responseStatus)) ||
                    (statusFilter != null && statusFilter.test(responseStatus))) {
                    return CompletableFuture.completedFuture(backoff);
                }
            }
            return NULL_BACKOFF;
        };
    }

    private RetryStrategyBindingBuilder newBindingBuilder() {
        return new RetryStrategyBindingBuilder(this);
    }
}
