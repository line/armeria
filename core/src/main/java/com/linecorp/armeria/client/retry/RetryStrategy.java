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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

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
        return new RetryStrategy<I, O>() {
            @Override
            public CompletableFuture<Optional<Backoff>> shouldRetry(I request, O response) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
    }

    /**
     * Returns the {@link RetryStrategy} that retries the request with the {@link #defaultBackoff}
     * when the response matches {@link HttpStatusClass#SERVER_ERROR}.
     */
    static RetryStrategy<HttpRequest, HttpResponse> onServerErrorStatus() {
        return onServerErrorStatus(defaultBackoff);
    }

    /**
     * Returns the {@link RetryStrategy} that retries the request with the specified {@code backoff}
     * when the response matches {@link HttpStatusClass#SERVER_ERROR}.
     */
    static RetryStrategy<HttpRequest, HttpResponse> onServerErrorStatus(Backoff backoff) {
        requireNonNull(backoff, "backoff");
        return onStatus(status -> status.codeClass() == HttpStatusClass.SERVER_ERROR ? Optional.of(backoff)
                                                                                     : Optional.empty());
    }

    /**
     * Returns the {@link RetryStrategy} that decides to retry the request using
     * {@code statusBasedBackoffFunction}.
     *
     * @param statusBasedBackoffFunction the {@link Function} that returns {@code Optional<Backoff>} according
     *                                   to the {@link HttpStatus}
     */
    static RetryStrategy<HttpRequest, HttpResponse> onStatus(
            Function<HttpStatus, Optional<Backoff>> statusBasedBackoffFunction) {
        return new HttpStatusBasedRetryStrategy(statusBasedBackoffFunction);
    }

    /**
     * Returns {@link CompletableFuture} with {@link Optional} that contains {@link Backoff} which will be
     * used for retry. If the condition does not match, this needs to return {@link Optional#empty()}
     * to stop retry attempt. Note that {@link ResponseTimeoutException} is not retriable for
     * the whole retry, but each attempt.
     *
     * @see RetryingClientBuilder#responseTimeoutMillisForEachAttempt(long)
     */
    CompletableFuture<Optional<Backoff>> shouldRetry(I request, O response);
}
