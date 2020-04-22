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

import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

/**
 * A builder which creates a {@link RetryRule} used for building {@link RetryStrategy}.
 *
 * @see RetryStrategyBuilder#on(RetryRule)
 */
public final class RetryRuleBuilder extends AbstractRetryStrategyBindingBuilder {

    private static final Backoff NO_RETRY = numAttemptsSoFar -> -1;

    private static final Set<HttpMethod> IDEMPOTENT_METHODS =
            ImmutableSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE);

    private final ImmutableSet.Builder<HttpStatusClass> statusClassesBuilder = ImmutableSet.builder();
    private final ImmutableSet.Builder<HttpStatus> statusesBuilder = ImmutableSet.builder();

    private Set<HttpMethod> methods = HttpMethod.knownMethods();
    private boolean isMethodsSet;

    @Nullable
    private Predicate<HttpStatus> statusFilter;
    @Nullable
    private Predicate<Throwable> exceptionFilter;

    @Override
    public RetryRuleBuilder onIdempotentMethods() {
        return onMethods(IDEMPOTENT_METHODS);
    }

    @Override
    public RetryRuleBuilder onMethods(HttpMethod... methods) {
        return onMethods(ImmutableSet.copyOf(requireNonNull(methods, "methods")));
    }

    @Override
    public RetryRuleBuilder onMethods(Iterable<HttpMethod> methods) {
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

    @Override
    public RetryRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return onStatusClass(ImmutableSet.copyOf(requireNonNull(statusClasses, "statusClasses")));
    }

    @Override
    public RetryRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        requireNonNull(statusClasses, "statusClasses");
        checkArgument(!Iterables.isEmpty(statusClasses), "statusClasses can't be empty");

        statusClassesBuilder.addAll(statusClasses);
        return this;
    }

    @Override
    public RetryRuleBuilder onServerErrorStatus() {
        return onStatusClass(HttpStatusClass.SERVER_ERROR);
    }

    @Override
    public RetryRuleBuilder onStatus(HttpStatus... statuses) {
        return onStatus(ImmutableSet.copyOf(requireNonNull(statuses, "statuses")));
    }

    @Override
    public RetryRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        requireNonNull(statuses, "statuses");
        checkArgument(!Iterables.isEmpty(statuses), "statuses can't be empty");

        statusesBuilder.addAll(statuses);
        return this;
    }

    @Override
    public RetryRuleBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        requireNonNull(statusFilter, "statuses");
        if (this.statusFilter != null) {
            this.statusFilter = this.statusFilter.or(statusFilter);
        } else {
            @SuppressWarnings("unchecked")
            final Predicate<HttpStatus> cast = (Predicate<HttpStatus>) statusFilter;
            this.statusFilter = cast;
        }
        return this;
    }

    @Override
    public RetryRuleBuilder onException(Class<? extends Throwable> exception) {
        requireNonNull(exception, "exception");
        return onException(exception::isInstance);
    }

    @Override
    public RetryRuleBuilder onException(Predicate<? super Throwable> exceptionFilter) {
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

    @Override
    public RetryRuleBuilder onException() {
        return onException(unused -> true);
    }

    @Override
    public RetryRuleBuilder onUnProcessed() {
        return onException(UnprocessedRequestException.class);
    }

    /**
     * Sets the {@linkplain Backoff#ofDefault() default backoff} and returns a newly created {@link RetryRule}.
     */
    public RetryRule thenDefaultBackoff() {
        return thenBackoff(Backoff.ofDefault());
    }

    /**
     * Sets the specified {@link Backoff} and returns a newly created {@link RetryRule}.
     */
    public RetryRule thenBackoff(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return build(backoff);
    }

    /**
     * Sets a {@link Backoff} that will never wait and limit the number of attempts up to the specified value.
     * Returns a newly created {@link RetryRule}.
     */
    public RetryRule thenImmediately(int maxAttempts) {
        checkArgument(maxAttempts > 0, "maxAttempts: %s (expected: > 0)", maxAttempts);
        final Backoff backOff = Backoff.withoutDelay().withMaxAttempts(maxAttempts);
        return build(backOff);
    }

    /**
     * Disables retry for this {@link RetryStrategy} and returns a newly created {@link RetryRule}.
     */
    public RetryRule thenStop() {
        return build(NO_RETRY);
    }

    private RetryRule build(Backoff backoff) {
        final Set<HttpMethod> methods = Sets.immutableEnumSet(this.methods);
        final Set<HttpStatusClass> statusClasses = Sets.immutableEnumSet(statusClassesBuilder.build());
        final Set<HttpStatus> statuses = statusesBuilder.build();
        final Predicate<HttpStatus> statusFilter = this.statusFilter;
        final Predicate<Throwable> exceptionFilter = this.exceptionFilter;

        if (backoff != NO_RETRY && exceptionFilter == null && statusFilter == null &&
            statuses.isEmpty() && statusClasses.isEmpty()) {
            throw new IllegalStateException(
                    "Should set at least one of status, status class and an expected exception type " +
                    "if a backoff was set.");
        }

        return new RetryRule(methods, statusClasses, statuses, backoff, statusFilter, exceptionFilter);
    }
}
