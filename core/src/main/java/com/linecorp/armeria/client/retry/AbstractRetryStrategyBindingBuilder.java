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

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

/**
 * A builder class for binding a {@link RetryStrategy} fluently.
 */
public abstract class AbstractRetryStrategyBindingBuilder {

    AbstractRetryStrategyBindingBuilder() {}

    /**
     * Adds the idempotent HTTP methods for a {@link RetryStrategy} which will retry
     * if the request HTTP method is idempotent.
     */
    public abstract AbstractRetryStrategyBindingBuilder onIdempotentMethods();

    /**
     * Adds the specified HTTP methods for a {@link RetryStrategy} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
    public abstract AbstractRetryStrategyBindingBuilder onMethods(HttpMethod... methods);

    /**
     * Adds the specified HTTP methods for a {@link RetryStrategy} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
    public abstract AbstractRetryStrategyBindingBuilder onMethods(Iterable<HttpMethod> methods);

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryStrategy} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public abstract AbstractRetryStrategyBindingBuilder onStatusClass(HttpStatusClass... statusClasses);

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryStrategy} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    public abstract AbstractRetryStrategyBindingBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses);

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link RetryStrategy} which will retry
     * if the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    public abstract AbstractRetryStrategyBindingBuilder onServerErrorStatus();

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryStrategy} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public abstract AbstractRetryStrategyBindingBuilder onStatus(HttpStatus... statuses);

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryStrategy} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    public abstract AbstractRetryStrategyBindingBuilder onStatus(Iterable<HttpStatus> statuses);

    /**
     * Adds the specified {@code statusFilter} for a {@link RetryStrategy} which will retry
     * if the response status matches the specified {@code statusFilter}.
     */
    public abstract AbstractRetryStrategyBindingBuilder onStatus(Predicate<? super HttpStatus> statusFilter);

    /**
     * Adds the specified exception type for a {@link RetryStrategy} which will retry
     * if an {@link Exception} is raised and that is instance of the specified {@code exception}.
     */
    public abstract AbstractRetryStrategyBindingBuilder onException(Class<? extends Throwable> exception);

    /**
     * Adds the specified {@code exceptionFilter} for a {@link RetryStrategy} which will retry
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    public abstract AbstractRetryStrategyBindingBuilder onException(
            Predicate<? super Throwable> exceptionFilter);

    /**
     * Makes a {@link RetryStrategy} retry on any {@link Exception}.
     */
    public abstract AbstractRetryStrategyBindingBuilder onException();

    /**
     * Makes a {@link RetryStrategy} retry on an {@link UnprocessedRequestException}.
     */
    public abstract AbstractRetryStrategyBindingBuilder onUnProcessed();
}
