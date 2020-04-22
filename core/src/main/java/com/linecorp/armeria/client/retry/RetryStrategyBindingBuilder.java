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

import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

/**
 * A builder class for binding a {@link RetryStrategy} fluently.
 */
public final class RetryStrategyBindingBuilder extends AbstractRetryStrategyBindingBuilder {

    private final RetryRuleBuilder retryRuleBuilder = new RetryRuleBuilder();
    private final RetryStrategyBuilder retryStrategyBuilder;

    RetryStrategyBindingBuilder(RetryStrategyBuilder retryStrategyBuilder) {
        this.retryStrategyBuilder = retryStrategyBuilder;
    }

    /**
     * Sets the {@linkplain Backoff#ofDefault() default backoff} and returns the {@link RetryStrategyBuilder}
     * that this {@link RetryStrategyBindingBuilder} was created from.
     */
    public RetryStrategyBuilder thenDefaultBackoff() {
        return addRetryRule(retryRuleBuilder.thenDefaultBackoff());
    }

    /**
     * Sets the specified {@link Backoff} and returns the {@link RetryStrategyBuilder} that
     * this {@link RetryStrategyBindingBuilder} was created from.
     */
    public RetryStrategyBuilder thenBackoff(Backoff backoff) {
        return addRetryRule(retryRuleBuilder.thenBackoff(backoff));
    }

    /**
     * Sets a {@link Backoff} that will never wait and limit the number of attempts up to the specified value.
     * Returns the {@link RetryStrategyBuilder} that this {@link RetryStrategyBindingBuilder} was created from.
     */
    public RetryStrategyBuilder thenImmediately(int maxAttempts) {
        return addRetryRule(retryRuleBuilder.thenImmediately(maxAttempts));
    }

    /**
     * Disables retry for this {@link RetryStrategy} and returns the {@link RetryStrategyBuilder} that
     * this {@link RetryStrategyBindingBuilder} was created from.
     */
    public RetryStrategyBuilder thenStop() {
        return addRetryRule(retryRuleBuilder.thenStop());
    }

    private RetryStrategyBuilder addRetryRule(RetryRule retryRule) {
        retryStrategyBuilder.on(retryRule);
        return retryStrategyBuilder;
    }

    // Methods that were overridden to change the return type and delegates to retryRuleBuilder

    @Override
    public RetryStrategyBindingBuilder onIdempotentMethods() {
        retryRuleBuilder.onIdempotentMethods();
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onMethods(HttpMethod... methods) {
        retryRuleBuilder.onMethods(methods);
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onMethods(Iterable<HttpMethod> methods) {
        retryRuleBuilder.onMethods(methods);
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onStatusClass(HttpStatusClass... statusClasses) {
        retryRuleBuilder.onStatusClass(statusClasses);
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        retryRuleBuilder.onStatusClass(statusClasses);
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onServerErrorStatus() {
        retryRuleBuilder.onServerErrorStatus();
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onStatus(HttpStatus... statuses) {
        retryRuleBuilder.onStatus(statuses);
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onStatus(Iterable<HttpStatus> statuses) {
        retryRuleBuilder.onStatus(statuses);
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        retryRuleBuilder.onStatus(statusFilter);
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onException(Class<? extends Throwable> exception) {
        retryRuleBuilder.onException(exception);
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onException(Predicate<? super Throwable> exceptionFilter) {
        retryRuleBuilder.onException(exceptionFilter);
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onException() {
        retryRuleBuilder.onException();
        return this;
    }

    @Override
    public RetryStrategyBindingBuilder onUnProcessed() {
        retryRuleBuilder.onUnProcessed();
        return this;
    }
}
