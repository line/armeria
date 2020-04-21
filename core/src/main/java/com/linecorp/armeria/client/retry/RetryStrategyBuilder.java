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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A builder which builds {@link RetryStrategy}.
 */
public final class RetryStrategyBuilder {

    static final CompletableFuture<Backoff> NULL_BACKOFF = CompletableFuture.completedFuture(null);
    private static final RetryStrategy[] EMPTY_RETRY_STRATEGIES = new RetryStrategy[0];

    private final ImmutableList.Builder<RetryStrategy> retryStrategiesBuilder = ImmutableList.builder();
    private final Set<HttpStatusClass> statusClasses = EnumSet.noneOf(HttpStatusClass.class);
    private final Set<HttpStatus> statuses = new HashSet<>();

    RetryStrategyBuilder() {}

    /**
     * Adds a {@link RetryStrategy} that retries with the specified {@link Backoff}
     * when the class of the response status is the specified {@link HttpStatusClass}.
     */
    public RetryStrategyBuilder onStatusClass(HttpStatusClass statusClass, Backoff backoff) {
        requireNonNull(statusClass, "statusClass");
        requireNonNull(backoff, "backoff");
        checkArgument(!statusClasses.contains(statusClass), "%s is already set", statusClass);
        statusClasses.add(statusClass);

        final RetryStrategy retryStrategy = (ctx, cause) -> {
            if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                final HttpStatusClass codeClass = ctx.log().partial().responseHeaders().status().codeClass();
                if (codeClass == statusClass) {
                    return CompletableFuture.completedFuture(backoff);
                }
            }
            return NULL_BACKOFF;
        };
        return addRetryStrategy(retryStrategy);
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the {@linkplain Backoff#ofDefault() default backoff}
     * when the class of response status is the specified {@link HttpStatusClass}.
     */
    public RetryStrategyBuilder onStatusClass(HttpStatusClass statusClass) {
        return onStatusClass(statusClass, Backoff.ofDefault());
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the specified {@link Backoff}
     * when the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    public RetryStrategyBuilder onServerErrorStatus(Backoff backoff) {
        return onStatusClass(HttpStatusClass.SERVER_ERROR, backoff);
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the {@linkplain Backoff#ofDefault() default backoff}
     * when the class of response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    public RetryStrategyBuilder onServerErrorStatus() {
        return onStatusClass(HttpStatusClass.SERVER_ERROR);
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the specified {@link Backoff}
     * when the response status matches the specified {@code statusFilter}.
     */
    public RetryStrategyBuilder onStatus(Predicate<? super HttpStatus> statusFilter, Backoff backoff) {
        requireNonNull(statusFilter, "status");
        requireNonNull(backoff, "backoff");

        final RetryStrategy retryStrategy = (ctx, cause) -> {
            if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                final HttpStatus responseStatus = ctx.log().partial().responseHeaders().status();
                if (statusFilter.test(responseStatus)) {
                    return CompletableFuture.completedFuture(backoff);
                }
            }
            return NULL_BACKOFF;
        };
        return addRetryStrategy(retryStrategy);
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the specified {@link Backoff}
     * when the response status is the specified {@link HttpStatus}.
     */
    public RetryStrategyBuilder onStatus(HttpStatus status, Backoff backoff) {
        requireNonNull(status, "status");
        requireNonNull(backoff, "backoff");
        checkArgument(!statuses.contains(status), "%s is already set", status);
        statuses.add(status);

        return onStatus(status::equals, backoff);
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the {@linkplain Backoff#ofDefault() default backoff}
     * when the response status is the specified {@link HttpStatus}.
     */
    public RetryStrategyBuilder onStatus(HttpStatus status) {
        return onStatus(status, Backoff.ofDefault());
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the specified {@link Backoff}
     * when an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    public RetryStrategyBuilder onException(Predicate<? super Throwable> exceptionFilter, Backoff backoff) {
        requireNonNull(exceptionFilter, "exceptionFilter");
        requireNonNull(backoff, "backoff");

        final RetryStrategy retryStrategy = (ctx, cause) -> {
            if (cause != null && exceptionFilter.test(Exceptions.peel(cause))) {
                return CompletableFuture.completedFuture(backoff);
            }
            return NULL_BACKOFF;
        };
        return addRetryStrategy(retryStrategy);
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the {@linkplain Backoff#ofDefault() default backoff}
     * when an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    public RetryStrategyBuilder onException(Predicate<? super Throwable> exceptionFilter) {
        return onException(exceptionFilter, Backoff.ofDefault());
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the specified {@link Backoff}
     * when an {@link Exception} is raised and that is instance of the specified {@code exception}.
     */
    public RetryStrategyBuilder onException(Class<? extends Throwable> exception, Backoff backoff) {
        return onException(exception::isInstance, backoff);
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the {@link Backoff#ofDefault() default backoff}
     * when an {@link Exception} is raised and that is instance of the specified {@code exception}.
     */
    public RetryStrategyBuilder onException(Class<? extends Throwable> exception) {
        return onException(exception::isInstance, Backoff.ofDefault());
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the specified {@link Backoff#ofDefault()}
     * when any {@link Exception} is raised.
     */
    public RetryStrategyBuilder onException(Backoff backoff) {
        return onException(unused -> true, backoff);
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the {@link Backoff#ofDefault() default backoff}
     * when any {@link Exception} is raised.
     */
    public RetryStrategyBuilder onException() {
        return onException(Backoff.ofDefault());
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the specified {@link Backoff}
     * when an {@link UnprocessedRequestException} is raised.
     */
    public RetryStrategyBuilder onUnProcessed(Backoff backoff) {
        return onException(UnprocessedRequestException.class, backoff);
    }

    /**
     * Adds a {@link RetryStrategy} that retries with the {@link Backoff#ofDefault() default backoff}
     * when an {@link UnprocessedRequestException} is raised.
     */
    public RetryStrategyBuilder onUnProcessed() {
        return onException(UnprocessedRequestException.class, Backoff.ofDefault());
    }

    /**
     * Add a {@link RetryStrategy} fluently.
     */
    public RetryStrategyBuilder on(Consumer<RetryStrategyBindingBuilder> customizer) {
        final RetryStrategyBindingBuilder bindingBuilder = new RetryStrategyBindingBuilder();
        customizer.accept(bindingBuilder);
        addRetryStrategy(bindingBuilder.build());
        return this;
    }

    /**
     * Adds a {@link RetryStrategy}.
     */
    public RetryStrategyBuilder addRetryStrategy(RetryStrategy retryStrategy) {
        retryStrategiesBuilder.add(requireNonNull(retryStrategy, "retryStrategy"));
        return this;
    }

    /**
     * Returns a newly-created {@link RetryStrategy} based on the {@link RetryStrategy}s set so far.
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
}
