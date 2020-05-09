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
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * Determines whether a failed request should be retried.
 * If you need to determine by looking into the {@link Response}, use {@link RetryStrategyWithContent}.
 */
@FunctionalInterface
public interface RetryStrategy {

    /**
     * Returns a {@link RetryStrategy} that never retries.
     */
    static RetryStrategy never() {
        return (ctx, cause) -> CompletableFuture.completedFuture(null);
    }

    /**
     * Returns a {@link RetryStrategy} that retries with {@link Backoff#ofDefault()}
     * only on an {@link UnprocessedRequestException}.
     */
    static RetryStrategy onUnprocessed() {
        return onUnprocessed(Backoff.ofDefault());
    }

    /**
     * Returns a {@link RetryStrategy} that retries with the specified {@link Backoff}
     * only on an {@link UnprocessedRequestException}.
     */
    static RetryStrategy onUnprocessed(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return onException(cause -> cause instanceof UnprocessedRequestException ? backoff : null);
    }

    /**
     * Returns a {@link RetryStrategy} that retries with {@link Backoff#ofDefault()} on any {@link Exception}.
     */
    static RetryStrategy onException() {
        return onException(cause -> Backoff.ofDefault());
    }

    /**
     * Returns a {@link RetryStrategy} that decides to retry using the specified {@code backoffFunction}.
     *
     * @param backoffFunction A {@link Function} that returns the {@link Backoff} or {@code null} (no retry)
     *                        according to the given {@link Throwable}
     */
    static RetryStrategy onException(Function<? super Throwable, ? extends Backoff> backoffFunction) {
        requireNonNull(backoffFunction, "backoffFunction");
        return onStatus((status, thrown) -> {
            if (thrown != null) {
                return backoffFunction.apply(Exceptions.peel(thrown));
            }
            return null;
        });
    }

    /**
     * Returns a {@link RetryStrategy} that retries with the {@link Backoff#ofDefault()}
     * when the response status is 5xx (server error) or an {@link Exception} is raised.
     */
    static RetryStrategy onServerErrorStatus() {
        return onServerErrorStatus(Backoff.ofDefault());
    }

    /**
     * Returns the {@link RetryStrategy} that retries the request with the specified {@code backoff}
     * when the response status is 5xx (server error) or an {@link Exception} is raised.
     */
    static RetryStrategy onServerErrorStatus(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return onStatus((status, thrown) -> {
            if (thrown != null || (status != null && status.isServerError())) {
                return backoff;
            }
            return null;
        });
    }

    /**
     * Returns a {@link RetryStrategy} that decides to retry using the specified {@code backoffFunction}.
     *
     * @param backoffFunction A {@link BiFunction} that returns the {@link Backoff} or {@code null} (no retry)
     *                        according to the given {@link HttpStatus} and {@link Throwable}
     */
    static RetryStrategy onStatus(
            BiFunction<? super HttpStatus, ? super Throwable, ? extends Backoff> backoffFunction) {
        // TODO(trustin): Apply a different backoff for UnprocessedRequestException.
        return new HttpStatusBasedRetryStrategy(backoffFunction);
    }

    /**
     * Tells whether the request sent with the specified {@link ClientRequestContext} requires a retry or not.
     * Implement this method to return a {@link CompletionStage} and to complete it with a desired
     * {@link Backoff}. To stop trying further, complete it with {@code null}.
     *
     * <p>To retrieve the {@link ResponseHeaders}, you can use the specified {@link ClientRequestContext}:
     * <pre>{@code
     * CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
     *     if (cause != null) {
     *         return CompletableFuture.completedFuture(backoff);
     *     }
     *
     *     ResponseHeaders responseHeaders = ctx.log().responseHeaders();
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
     */
    CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause);
}
