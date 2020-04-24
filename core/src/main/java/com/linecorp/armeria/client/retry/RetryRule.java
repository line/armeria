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

import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

/**
 * A rule for {@link RetryStrategy}.
 */
public final class RetryRule {

    /**
     * Adds the idempotent HTTP methods for a {@link RetryStrategy} which will retry
     * if the request HTTP method is idempotent.
     */
    public static RetryRuleBuilder onIdempotentMethods() {
        return newRuleBuilder().onIdempotentMethods();
    }

    /**
     * Adds the specified HTTP methods for a {@link RetryStrategy} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
    public static RetryRuleBuilder onMethods(HttpMethod... methods) {
        return newRuleBuilder().onMethods(methods);
    }

    /**
     * Adds the specified HTTP methods for a {@link RetryStrategy} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
    public static RetryRuleBuilder onMethods(Iterable<HttpMethod> methods) {
        return newRuleBuilder().onMethods(methods);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryStrategy} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public static RetryRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return newRuleBuilder().onStatusClass(statusClasses);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryStrategy} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public static RetryRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return newRuleBuilder().onStatusClass(statusClasses);
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link RetryStrategy} which will retry
     * if the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    public static RetryRuleBuilder onServerErrorStatus() {
        return newRuleBuilder().onServerErrorStatus();
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryStrategy} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public static RetryRuleBuilder onStatus(HttpStatus... statuses) {
        return newRuleBuilder().onStatus(statuses);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryStrategy} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public static RetryRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        return newRuleBuilder().onStatus(statuses);
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link RetryStrategy} which will retry
     * if the response status matches the specified {@code statusFilter}.
     */
    public static RetryRuleBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        return newRuleBuilder().onStatus(statusFilter);
    }

    /**
     * Adds the specified exception type for a {@link RetryStrategy} which will retry
     * if an {@link Exception} is raised and that is instance of the specified {@code exception}.
     */
    public static RetryRuleBuilder onException(Class<? extends Throwable> exception) {
        return newRuleBuilder().onException(exception);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link RetryStrategy} which will retry
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    public static RetryRuleBuilder onException(Predicate<? super Throwable> exceptionFilter) {
        return newRuleBuilder().onException(exceptionFilter);
    }

    /**
     * Makes a {@link RetryStrategy} retry on any {@link Exception}.
     */
    public static RetryRuleBuilder onException() {
        return newRuleBuilder().onException();
    }

    /**
     * Makes a {@link RetryStrategy} retry on an {@link UnprocessedRequestException}.
     */
    public static RetryRuleBuilder onUnprocessed() {
        return newRuleBuilder().onUnprocessed();
    }

    private static RetryRuleBuilder newRuleBuilder() {
        return new RetryRuleBuilder();
    }

    private final Set<HttpMethod> methods;
    private final Set<HttpStatusClass> statusClasses;
    private final Set<HttpStatus> statuses;
    private final Backoff backoff;
    @Nullable
    private final Predicate<HttpStatus> statusFilter;
    @Nullable
    private final Predicate<Throwable> exceptionFilter;

    RetryRule(Set<HttpMethod> methods, Set<HttpStatusClass> statusClasses,
              Set<HttpStatus> statuses, Backoff backoff,
              @Nullable Predicate<HttpStatus> statusFilter,
              @Nullable Predicate<Throwable> exceptionFilter) {
        this.methods = methods;
        this.statusClasses = statusClasses;
        this.statuses = statuses;
        this.backoff = backoff;
        this.statusFilter = statusFilter;
        this.exceptionFilter = exceptionFilter;
    }

    Set<HttpMethod> methods() {
        return methods;
    }

    Set<HttpStatusClass> statusClasses() {
        return statusClasses;
    }

    Set<HttpStatus> statuses() {
        return statuses;
    }

    Backoff backoff() {
        return backoff;
    }

    @Nullable
    Predicate<HttpStatus> statusFilter() {
        return statusFilter;
    }

    @Nullable
    Predicate<Throwable> exceptionFilter() {
        return exceptionFilter;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("methods", methods)
                          .add("statusClasses", statusClasses)
                          .add("statuses", statuses)
                          .add("backoff", backoff)
                          .add("statusFilter", statusFilter)
                          .add("exceptionFilter", exceptionFilter)
                          .omitNullValues()
                          .toString();
    }
}
