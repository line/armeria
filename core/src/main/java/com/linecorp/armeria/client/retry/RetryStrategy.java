/*
 * Copyright 2017 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * Determines whether a failed request should be retried.
 * If you need to determine by looking into the {@link Response}, use {@link RetryStrategyWithContent}.
 */
@FunctionalInterface
public interface RetryStrategy {

    /**
     * The default {@link Backoff} implementation.
     *
     * @deprecated Use {@link Backoff#ofDefault()}.
     */
    @Deprecated
    Backoff defaultBackoff = Backoff.ofDefault();

    /**
     * A {@link RetryStrategy} that defines a retry should not be performed.
     */
    static RetryStrategy never() {
        return (ctx, cause) -> CompletableFuture.completedFuture(null);
    }

    /**
     * A {@link RetryStrategy} that retries only on {@link UnprocessedRequestException} with
     * the {@link Backoff#ofDefault()}.
     */
    static RetryStrategy onUnprocessed() {
        return onUnprocessed(Backoff.ofDefault());
    }

    /**
     * A {@link RetryStrategy} that retries only on {@link UnprocessedRequestException} with the specified
     * {@link Backoff}.
     */
    static RetryStrategy onUnprocessed(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return onStatus((status, thrown) -> {
            if (thrown != null && Exceptions.peel(thrown) instanceof UnprocessedRequestException) {
                return backoff;
            }
            return null;
        });
    }

    /**
     * Returns the {@link RetryStrategy} that retries the request with the {@link Backoff#ofDefault()}
     * when the response status matches {@link HttpStatusClass#SERVER_ERROR} or an {@link Exception} is raised.
     */
    static RetryStrategy onServerErrorStatus() {
        return onServerErrorStatus(Backoff.ofDefault());
    }

    /**
     * Returns the {@link RetryStrategy} that retries the request with the specified {@code backoff}
     * when the response status matches {@link HttpStatusClass#SERVER_ERROR} or an {@link Exception} is raised.
     */
    static RetryStrategy onServerErrorStatus(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return onStatus((status, thrown) -> {
            if (thrown != null || (status != null && status.codeClass() == HttpStatusClass.SERVER_ERROR)) {
                return backoff;
            }
            return null;
        });
    }

    /**
     * Returns the {@link RetryStrategy} that decides to retry the request using the specified
     * {@code backoffFunction}.
     *
     * @param backoffFunction the {@link BiFunction} that returns the {@link Backoff} or {@code null}
     *                        according to the {@link HttpStatus} and {@link Throwable}
     */
    static RetryStrategy onStatus(
            BiFunction<HttpStatus, Throwable, Backoff> backoffFunction) {
        // TODO(trustin): Apply a different backoff for UnprocessedRequestException.
        return new HttpStatusBasedRetryStrategy(backoffFunction);
    }

    /**
     * Returns a {@link CompletionStage} that contains {@link Backoff} which will be used for retry.
     * If the condition does not match, this will return a {@link CompletionStage} completed with
     * {@code null} to stop retry attempt.
     * Note that {@link ResponseTimeoutException} is not retriable for the whole retry,
     * but only for each attempt.
     * To retrieve the response {@link HttpHeaders}, you can use the specified {@link ClientRequestContext}:
     *
     * <pre>{@code
     * CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
     *     if (cause != null) {
     *         return CompletableFuture.completedFuture(backoff);
     *     }
     *
     *     HttpHeaders responseHeaders = ctx.log().responseHeaders();
     *     if (responseHeaders.status().codeClass() == HttpStatusClass.SERVER_ERROR) {
     *         return CompletableFuture.completedFuture(backoff);
     *     }
     *     ...
     * }
     * }</pre>
     *
     * @param ctx the {@link ClientRequestContext} of this request
     * @param cause the {@link Throwable} which is raised while sending a request. {@code null} it there's no
     *              exception.
     *
     * @see RetryingClientBuilder#responseTimeoutMillisForEachAttempt(long)
     *
     * @see <a href="https://line.github.io/armeria/advanced-retry.html#per-attempt-timeout">Per-attempt
     *      timeout</a>
     */
    CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause);
}
