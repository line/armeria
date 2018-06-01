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

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.circuitbreaker.FailFastException;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * Determines whether a failed request should be retried.
 * @param <I> the request type
 * @param <O> the response type
 */
@FunctionalInterface
public interface RetryStrategy<I extends Request, O extends Response> {

    Backoff defaultBackoff = Backoff.of(Flags.defaultBackoffSpec());

    /**
     * A {@link RetryStrategy} that defines a retry should not be performed.
     */
    static <I extends Request, O extends Response> RetryStrategy<I, O> never() {
        return (request, response) -> CompletableFuture.completedFuture(null);
    }

    /**
     * Returns the {@link RetryStrategy} that retries the request with the {@link #defaultBackoff}
     * when the response matches {@link HttpStatusClass#SERVER_ERROR} or an {@link Exception} is raised.
     */
    static RetryStrategy<HttpRequest, HttpResponse> onServerErrorStatus() {
        return onServerErrorStatus(defaultBackoff);
    }

    /**
     * Returns the {@link RetryStrategy} that retries the request with the specified {@code backoff}
     * when the response matches {@link HttpStatusClass#SERVER_ERROR} or an {@link Exception} is raised.
     */
    static RetryStrategy<HttpRequest, HttpResponse> onServerErrorStatus(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return onStatus((status, thrown) -> {
            if ((thrown != null && !(Exceptions.peel(thrown) instanceof FailFastException)) ||
                (status != null && status.codeClass() == HttpStatusClass.SERVER_ERROR)) {
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
    static RetryStrategy<HttpRequest, HttpResponse> onStatus(
            BiFunction<HttpStatus, Throwable, Backoff> backoffFunction) {
        return new HttpStatusBasedRetryStrategy(backoffFunction);
    }

    /**
     * Returns a {@link CompletionStage} that contains {@link Backoff} which will be used for retry.
     * If the condition does not match, this will return {@code null} to stop retry attempt.
     * Note that {@link ResponseTimeoutException} is not retriable for the whole retry, but each attempt.
     *
     * @see RetryingClientBuilder#responseTimeoutMillisForEachAttempt(long)
     *
     * @see <a href="https://line.github.io/armeria/advanced-retry.html#per-attempt-timeout">Per-attempt
     *      timeout</a>
     */
    CompletionStage<Backoff> shouldRetry(I request, O response);
}
