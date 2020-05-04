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
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A builder which creates a {@link RetryRule}.
 */
public final class RetryRuleBuilder {

    private static final CompletableFuture<RetryRuleDecision> NEXT =
            CompletableFuture.completedFuture(RetryRuleDecision.next());
    private static final CompletableFuture<RetryRuleDecision> DEFAULT_DECISION =
           CompletableFuture.completedFuture(RetryRuleDecision.DEFAULT);

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

    RetryRuleBuilder() {}

    /**
     * Adds the <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotent</a>
     * HTTP methods, which should not have any side-effects (except for keeping statistics),
     * for a {@link RetryRule} which will retry if the request HTTP method is idempotent.
     */
    public RetryRuleBuilder onIdempotentMethods() {
        return onMethods(IDEMPOTENT_METHODS);
    }

    /**
     * Adds the specified HTTP methods for a {@link RetryRule} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
    public RetryRuleBuilder onMethods(HttpMethod... methods) {
        return onMethods(ImmutableSet.copyOf(requireNonNull(methods, "methods")));
    }

    /**
     * Adds the specified HTTP methods for a {@link RetryRule} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
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

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public RetryRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return onStatusClass(ImmutableSet.copyOf(requireNonNull(statusClasses, "statusClasses")));
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public RetryRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        requireNonNull(statusClasses, "statusClasses");
        checkArgument(!Iterables.isEmpty(statusClasses), "statusClasses can't be empty");

        statusClassesBuilder.addAll(statusClasses);
        return this;
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link RetryRule} which will retry
     * if the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    public RetryRuleBuilder onServerErrorStatus() {
        return onStatusClass(HttpStatusClass.SERVER_ERROR);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public RetryRuleBuilder onStatus(HttpStatus... statuses) {
        return onStatus(ImmutableSet.copyOf(requireNonNull(statuses, "statuses")));
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public RetryRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        requireNonNull(statuses, "statuses");
        checkArgument(!Iterables.isEmpty(statuses), "statuses can't be empty");

        statusesBuilder.addAll(statuses);
        return this;
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link RetryRule} which will retry
     * if the response status matches the specified {@code statusFilter}.
     */
    public RetryRuleBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        requireNonNull(statusFilter, "statusFilter");
        if (this.statusFilter != null) {
            this.statusFilter = this.statusFilter.or(statusFilter);
        } else {
            @SuppressWarnings("unchecked")
            final Predicate<HttpStatus> cast = (Predicate<HttpStatus>) statusFilter;
            this.statusFilter = cast;
        }
        return this;
    }

    /**
     * Adds the specified exception type for a {@link RetryRule} which will retry
     * if an {@link Exception} is raised and that is instance of the specified {@code exception}.
     */
    public RetryRuleBuilder onException(Class<? extends Throwable> exception) {
        requireNonNull(exception, "exception");
        return onException(exception::isInstance);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link RetryRule} which will retry
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
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

    /**
     * Makes a {@link RetryRule} retry on any {@link Exception}.
     */
    public RetryRuleBuilder onException() {
        return onException(unused -> true);
    }

    /**
     * Makes a {@link RetryRule} retry on an {@link UnprocessedRequestException} which means that the request
     * has not been processed by the server. Therefore, you can safely retry the request without worrying about
     * the idempotency of the request.
     */
    public RetryRuleBuilder onUnprocessed() {
        return onException(UnprocessedRequestException.class);
    }

    /**
     * Sets the {@linkplain Backoff#ofDefault() default backoff} and returns a newly created {@link RetryRule}.
     */
    public RetryRule thenBackoff() {
        return thenBackoff(Backoff.ofDefault());
    }

    /**
     * Sets the specified {@link Backoff} and returns a newly created {@link RetryRule}.
     */
    public RetryRule thenBackoff(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return build(RetryRuleDecision.retry(backoff));
    }

    /**
     * Returns a newly created {@link RetryRule} that never retries.
     */
    public RetryRule thenNoRetry() {
        return build(RetryRuleDecision.noRetry());
    }

    private RetryRule build(RetryRuleDecision decision) {
        final Set<HttpMethod> methods = Sets.immutableEnumSet(this.methods);
        final Set<HttpStatusClass> statusClasses = Sets.immutableEnumSet(statusClassesBuilder.build());
        final Set<HttpStatus> statuses = statusesBuilder.build();
        final Predicate<HttpStatus> statusFilter = this.statusFilter;
        final Predicate<Throwable> exceptionFilter = this.exceptionFilter;

        if (decision != RetryRuleDecision.noRetry() && exceptionFilter == null && statusFilter == null &&
            statuses.isEmpty() && statusClasses.isEmpty()) {
            throw new IllegalStateException(
                    "Should set at least one of status, status class and an expected exception type " +
                    "if a backoff was set.");
        }
        final CompletableFuture<RetryRuleDecision> decisionFuture;
        if (decision == RetryRuleDecision.DEFAULT) {
           decisionFuture = DEFAULT_DECISION;
        } else {
            decisionFuture = CompletableFuture.completedFuture(decision);
        }
        return (ctx, cause) -> {
            if (!methods.contains(ctx.request().method())) {
                return NEXT;
            }

            if (cause != null && exceptionFilter != null && exceptionFilter.test(Exceptions.peel(cause))) {
                return decisionFuture;
            }

            if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                final HttpStatus responseStatus = ctx.log().partial().responseHeaders().status();
                if (statusClasses != null && statusClasses.contains(responseStatus.codeClass())) {
                    return decisionFuture;
                }

                if ((statuses != null && statuses.contains(responseStatus)) ||
                    (statusFilter != null && statusFilter.test(responseStatus))) {
                    return decisionFuture;
                }
            }

            return NEXT;
        };
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("statusClasses", statusClassesBuilder.build())
                          .add("statuses", statusesBuilder.build())
                          .add("methods", methods)
                          .add("statusFilter", statusFilter)
                          .add("exceptionFilter", exceptionFilter)
                          .toString();
    }
}
