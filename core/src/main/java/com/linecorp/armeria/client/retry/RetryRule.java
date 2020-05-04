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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
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
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@link Backoff#ofDefault() default backoff} if the request HTTP method is
     * <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotent</a>
     * and an {@link UnprocessedRequestException} is raised or the class of the response status is
     * {@link HttpStatusClass#SERVER_ERROR}.
     *
     * <p>Note that a client can safely retry a failed request with this rule if an endpoint service produces
     * the same result (no side effects) on idempotent HTTP methods.
     *
     * <p>This method is shortcut for:
     * <pre>{@code
     * RetryRule.builder()
     *          .onIdempotentMethods()
     *          .onServerErrorStatus()
     *          .onUnprocessed()
     *          .thenBackoff();
     * }</pre>
     */
    static RetryRule failsafe() {
        return builder().onIdempotentMethods().onServerErrorStatus().onUnprocessed().thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with
     * the {@linkplain Backoff#ofDefault() default backoff} if the class of the response status is
     * the specified {@link HttpStatusClass}.
     */
    static RetryRule onStatusClass(HttpStatusClass statusClass) {
        return builder().onStatusClass(statusClass).thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with
     * the {@linkplain Backoff#ofDefault() defalut backoff} if the class of the response status is
     * one of the specified {@link HttpStatusClass}es.
     */
    static RetryRule onStatusClass(Iterable<HttpStatusClass> statusClasses) {
        return builder().onStatusClass(statusClasses).thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff}
     * if the class of the response status is {@link HttpStatusClass#SERVER_ERROR}.
     */
    static RetryRule onServerErrorStatus() {
        return builder().onServerErrorStatus().thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the response status is one of
     * the specified {@link HttpStatus}es.
     */
    static RetryRule onStatus(HttpStatus... statuses) {
        return builder().onStatus(statuses).thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the response status is one of
     * the specified {@link HttpStatus}es.
     */
    static RetryRule onStatus(Iterable<HttpStatus> statuses) {
        return builder().onStatus(statuses).thenBackoff();
    }

    /**
     * Returns a newly created a {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if the response status matches
     * the specified {@code statusFilter}.
     */
    static RetryRule onStatus(Predicate<? super HttpStatus> statusFilter) {
        return builder().onStatus(statusFilter).thenBackoff();
    }

    /**
     * Returns a newly created a {@link RetryRule} that will retry with
     * the {@linkplain Backoff#ofDefault() default backoff} if an {@link Exception} is raised and
     * that is instance of the specified {@code exception}.
     */
    static RetryRule onException(Class<? extends Throwable> exception) {
        return builder().onException(exception).thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRule} that will retry with the
     * {@linkplain Backoff#ofDefault() default backoff} if an {@link Exception} is raised and
     * the specified {@code exceptionFilter} returns {@code true}.
     */
    static RetryRule onException(Predicate<? super Throwable> exceptionFilter) {
        return builder().onException(exceptionFilter).thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRule} that retries with
     * {@linkplain Backoff#ofDefault() default backoff} on any {@link Exception}.
     * Note that this rule should be used carefully because it reties regardless of
     * <a href="https://developer.mozilla.org/en-US/docs/Glossary/Idempotent">idempotency</a>.
     */
    static RetryRule onException() {
        return builder().onException().thenBackoff();
    }

    /**
     * Returns a {@link RetryRule} that retries with the {@linkplain Backoff#ofDefault() default backoff}
     * on an {@link UnprocessedRequestException} which means that the request has not been processed by
     * the server. Therefore, you can safely retry the request without worrying about the idempotency of
     * the request.
     */
    static RetryRule onUnprocessed() {
        return builder().onUnprocessed().thenBackoff();
    }

    /**
     * Returns a newly created {@link RetryRuleBuilder}.
     */
    static RetryRuleBuilder builder() {
        return new RetryRuleBuilder();
    }

    /**
     * Returns a composed {@link RetryRule} that represents a logical OR of this {@link RetryRule} and another.
     * If this {@link RetryRule} completes with {@link RetryRuleDecision#next()}, then other {@link RetryRule}
     * is evaluated.
     */
    default RetryRule or(RetryRule other) {
        requireNonNull(other, "other");
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
     * To not retry, complete it with {@link RetryRuleDecision#noRetry()}.
     * To skip this {@link RetryRule} and find other {@link RetryRule}, complete it with
     * {@link RetryRuleDecision#next()}.
     * If the return value of the last {@link RetryRule} completes with {@link RetryRuleDecision#next()},
     * the request never retries.
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
     *         return CompletableFuture.completedFuture(RetryRuleDecision.noRetry());
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
