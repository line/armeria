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
import static com.linecorp.armeria.client.retry.RetryStrategyBuilder.NULL_BACKOFF;
import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.logging.RequestLogProperty;

/**
 * A builder class for binding a {@link RetryStrategy} fluently.
 */
public class RetryStrategyBindingBuilder {

    private static final Set<HttpMethod> IDEMPOTENT_METHODS =
            ImmutableSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE);

    private final RetryStrategyBuilder retryStrategyBuilder;
    private final ImmutableSet.Builder<HttpStatusClass> statusClassesBuilder = ImmutableSet.builder();
    private final ImmutableSet.Builder<HttpStatus> statusesBuilder = ImmutableSet.builder();

    private Set<HttpMethod> methods = HttpMethod.knownMethods();
    private boolean isMethodsSet;

    @Nullable
    private Predicate<Throwable> exceptionFilter;

    RetryStrategyBindingBuilder(RetryStrategyBuilder retryStrategyBuilder) {
        this.retryStrategyBuilder = retryStrategyBuilder;
    }

    /**
     * Adds the idempotent HTTP methods for a {@link RetryStrategy} which will retry
     * if the request HTTP method is idempotent.
     */
    public RetryStrategyBindingBuilder idempotentMethods() {
        return methods(IDEMPOTENT_METHODS);
    }

    /**
     * Adds the specified HTTP methods for a {@link RetryStrategy} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
    public RetryStrategyBindingBuilder methods(HttpMethod... methods) {
        return methods(ImmutableSet.copyOf(requireNonNull(methods, "methods")));
    }

    /**
     * Adds the specified HTTP methods for a {@link RetryStrategy} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
    public RetryStrategyBindingBuilder methods(Iterable<HttpMethod> methods) {
        requireNonNull(methods, "methods");
        checkArgument(!Iterables.isEmpty(methods), "methods can't be empty");

        if (isMethodsSet) {
            Iterables.addAll(this.methods, methods);
        } else {
            this.methods = Sets.newEnumSet(methods, HttpMethod.class);
            isMethodsSet = true;
        }
        return this;
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryStrategy} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public RetryStrategyBindingBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return onStatusClass(ImmutableSet.copyOf(requireNonNull(statusClasses, "statusClasses")));
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryStrategy} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public RetryStrategyBindingBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        requireNonNull(statusClasses, "statusClasses");
        checkArgument(!Iterables.isEmpty(statusClasses), "statusClasses can't be empty");

        statusClassesBuilder.addAll(statusClasses);
        return this;
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryStrategy} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public RetryStrategyBindingBuilder onStatus(HttpStatus... statuses) {
        return onStatus(ImmutableSet.copyOf(requireNonNull(statuses, "statuses")));
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryStrategy} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public RetryStrategyBindingBuilder onStatus(Iterable<HttpStatus> statuses) {
        requireNonNull(statuses, "statuses");
        checkArgument(!Iterables.isEmpty(statuses), "statuses can't be empty");

        statusesBuilder.addAll(statuses);
        return this;
    }

    /**
     * Adds the specified exception type for a {@link RetryStrategy} which will retry
     * if an {@link Exception} is raised and that is instance of the specified {@code exception}.
     */
    public RetryStrategyBindingBuilder onException(Class<? extends Throwable> exception) {
        requireNonNull(exception, "exception");
        return onException(exception::isInstance);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link RetryStrategy} which will retry
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    public RetryStrategyBindingBuilder onException(Predicate<? super Throwable> exceptionFilter) {
        requireNonNull(exceptionFilter, "exceptionFilter");
        if (this.exceptionFilter != null) {
            this.exceptionFilter = this.exceptionFilter.or(exceptionFilter);
        } else {
            @SuppressWarnings("unchecked")
            final Predicate<Throwable> cast = (Predicate<Throwable>) exceptionFilter;
            this.exceptionFilter = cast;
        }
        return this;
    }

    /**
     * Sets the specified {@link Backoff} and returns the {@link RetryStrategyBuilder} that this
     * {@link RetryStrategyBindingBuilder} was created from.
     *
     * @throws IllegalStateException if none of {@link HttpStatus}, {@link HttpStatusClass} or
     *                               an expected {@code exception} type is specified
     */
    public RetryStrategyBuilder build(Backoff backoff) {
        requireNonNull(backoff, "backoff");

        final Set<HttpMethod> methods = Sets.immutableEnumSet(this.methods);
        final Set<HttpStatusClass> statusClasses = Sets.immutableEnumSet(statusClassesBuilder.build());
        final Set<HttpStatus> statuses = statusesBuilder.build();
        final Predicate<Throwable> exceptionFilter = this.exceptionFilter;

        if (exceptionFilter == null && statuses.isEmpty() && statusClasses.isEmpty()) {
            throw new IllegalStateException(
                    "Should set at least one strategy of status, status class and an expected exception type " +
                    "before calling this.");
        }

        final RetryStrategy retryStrategy = (ctx, cause) -> {
            if (!methods.contains(ctx.request().method())) {
                return NULL_BACKOFF;
            }

            if (cause != null && exceptionFilter != null && exceptionFilter.test(cause)) {
                return CompletableFuture.completedFuture(backoff);
            }

            if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                final HttpStatus responseStatus = ctx.log().partial().responseHeaders().status();
                if (statusClasses != null && statusClasses.contains(responseStatus.codeClass())) {
                    return CompletableFuture.completedFuture(backoff);
                }
                if (statuses != null && statuses.contains(responseStatus)) {
                    return CompletableFuture.completedFuture(backoff);
                }
            }
            return NULL_BACKOFF;
        };
        return retryStrategyBuilder.addRetryStrategy(retryStrategy);
    }

    /**
     * Sets the {@link Backoff#ofDefault() default backoff} and returns the {@link RetryStrategyBuilder}
     * that this {@link RetryStrategyBindingBuilder} was created from.
     *
     * @throws IllegalStateException if none of {@link HttpStatus}, {@link HttpStatusClass}
     *                               or an expected {@code exception} type is specified
     */
    public RetryStrategyBuilder build() {
        return build(Backoff.ofDefault());
    }
}
