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

import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * Determines whether a failed request should be retried.
 * If you need to determine by looking into the {@link Response}, use {@link RetryRuleWithContent}.
 */
@FunctionalInterface
public interface RetryRule {

    /**
     * Adds the <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotent</a>
     * HTTP methods, which should not have any side-effects (except for keeping statistics),
     * for a {@link RetryRule} which will retry if the request HTTP method is idempotent.
     */
    static RetryRuleBuilder onIdempotentMethods() {
        return new RetryRuleBuilder().onIdempotentMethods();
    }

    /**
     * Adds the specified HTTP methods for a {@link RetryRule} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
    static RetryRuleBuilder onMethods(HttpMethod... methods) {
        return new RetryRuleBuilder().onMethods(methods);
    }

    /**
     * Adds the specified HTTP methods for a {@link RetryRule} which will retry
     * if the request HTTP method is one of the specified HTTP methods.
     */
    static RetryRuleBuilder onMethods(Iterable<HttpMethod> methods) {
        return new RetryRuleBuilder().onMethods(methods);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    static RetryRuleBuilder onStatusClass(HttpStatusClass... statusClasses) {
        return new RetryRuleBuilder().onStatusClass(statusClasses);
    }

    /**
     * Adds the specified {@link HttpStatusClass}es for a {@link RetryRule} which will retry
     * if the class of the response status is one of the specified {@link HttpStatusClass}es.
     */
    static RetryRuleBuilder onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return new RetryRuleBuilder().onStatusClass(statusClasses);
    }

    /**
     * Adds the {@link HttpStatusClass#SERVER_ERROR} for a {@link RetryRule} which will retry
     * if the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    static RetryRuleBuilder onServerErrorStatus() {
        return new RetryRuleBuilder().onServerErrorStatus();
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    static RetryRuleBuilder onStatus(HttpStatus... statuses) {
        return new RetryRuleBuilder().onStatus(statuses);
    }

    /**
     * Adds the specified {@link HttpStatus}es for a {@link RetryRule} which will retry
     * if the response status is one of the specified {@link HttpStatus}es.
     */
    static RetryRuleBuilder onStatus(Iterable<HttpStatus> statuses) {
        return new RetryRuleBuilder().onStatus(statuses);
    }

    /**
     * Adds the specified {@code statusFilter} for a {@link RetryRule} which will retry
     * if the response status matches the specified {@code statusFilter}.
     */
    static RetryRuleBuilder onStatus(Predicate<? super HttpStatus> statusFilter) {
        return new RetryRuleBuilder().onStatus(statusFilter);
    }

    /**
     * Adds the specified exception type for a {@link RetryRule} which will retry
     * if an {@link Exception} is raised and that is instance of the specified {@code exception}.
     */
    static RetryRuleBuilder onException(Class<? extends Throwable> exception) {
        return new RetryRuleBuilder().onException(exception);
    }

    /**
     * Adds the specified {@code exceptionFilter} for a {@link RetryRule} which will retry
     * if an {@link Exception} is raised and the specified {@code exceptionFilter} returns {@code true}.
     */
    static RetryRuleBuilder onException(Predicate<? super Throwable> exceptionFilter) {
        return new RetryRuleBuilder().onException(exceptionFilter);
    }

    /**
     * Makes a {@link RetryRule} retry on any {@link Exception}.
     */
    static RetryRuleBuilder onException() {
        return new RetryRuleBuilder().onException();
    }

    /**
     * Makes a {@link RetryRule} retry on an {@link UnprocessedRequestException} which means that the request
     * has not been processed by the server. Therefore, you can safely retry the request without worrying about
     * the idempotency of the request.
     */
    static RetryRuleBuilder onUnprocessed() {
        return new RetryRuleBuilder().onUnprocessed();
    }

    /**
     * Returns composed {@link RetryRule} that represents a logical OR of this {@link RetryRule} and another.
     * If this {@link RetryRule} completes with {@link RetryRuleDecision#retry(Backoff)} or
     * {@link RetryRuleDecision#stop()}, then other {@link RetryRule} is not evaluated.
     */
    default RetryRule or(RetryRule other) {
        return (ctx, cause) -> {
            final CompletionStage<RetryRuleDecision> decisionFuture = shouldRetry(ctx, cause);
            return decisionFuture.thenCompose(decision -> {
                if (decision != RetryRuleDecision.next()) {
                    return decisionFuture;
                } else {
                    return other.shouldRetry(ctx, cause);
                }
            });
        };
    }

    /**
     * Tells whether the request sent with the specified {@link ClientRequestContext} requires a retry or not.
     * Implement this method to return a {@link CompletionStage} and to complete it with a desired
     * {@link RetryRuleDecision#retry(Backoff)}.
     * To stop trying further, complete it with {@link RetryRuleDecision#stop()}.
     * To skip this {@link RetryRule} and find other {@link RetryRule}, complete it with
     * {@link RetryRuleDecision#next()}.
     *
     * <p>To retrieve the {@link ResponseHeaders}, you can use the specified {@link ClientRequestContext}:
     * <pre>{@code
     * CompletionStage<RetryRuleDecision> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
     *     if (cause != null) {
     *         return CompletableFuture.completedFuture(RetryRuleDecision.retry(backoff));
     *     }
     *
     *     ResponseHeaders responseHeaders = ctx.log().responseHeaders();
     *     if (responseHeaders.status().codeClass() == HttpStatusClass.SERVER_ERROR) {
     *         return CompletableFuture.completedFuture(RetryRuleDecision.retry(backoff));
     *     }
     *     if (responseHeaders.status() == HttpStatus.TOO_MANY_REQUESTS) {
     *         return CompletableFuture.completedFuture(RetryRuleDecision.stop());
     *     }
     *
     *     return CompletableFuture.completedFuture(RetryRuleDecision.next());
     * }
     * }</pre>
     *
     * @param ctx the {@link ClientRequestContext} of this request
     * @param cause the {@link Throwable} which is raised while sending a request. {@code null} if there's no
     *              exception.
     */
    CompletionStage<RetryRuleDecision> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause);
}
