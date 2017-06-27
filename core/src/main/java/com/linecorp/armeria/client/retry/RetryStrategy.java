/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.retry;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Determines whether a failed request should be retried.
 * @param <I> the request type
 * @param <O> the response type
 */
@FunctionalInterface
public interface RetryStrategy<I extends Request, O extends Response> {

    /**
     * A {@link RetryStrategy} that defines a retry should not be performed.
     */
    static <I extends Request, O extends Response> RetryStrategy<I, O> never() {
        return new RetryStrategy<I, O>() {
            @Override
            public CompletableFuture<Boolean> shouldRetry(I request, O response) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public boolean shouldRetry(I request, Throwable throwable) {
                return false;
            }
        };
    }

    /**
     * Returns the {@link RetryStrategy} that decides to retry the request using HTTP statuses.
     */
    static RetryStrategy<HttpRequest, HttpResponse> onStatus(HttpStatus... retryStatuses) {
        return onStatus(Arrays.asList(requireNonNull(retryStatuses, "retryStatuses")));
    }

    /**
     * Returns the {@link RetryStrategy} that decides to retry the request using HTTP statuses.
     */
    static RetryStrategy<HttpRequest, HttpResponse> onStatus(Iterable<HttpStatus> retryStatuses) {
        return new HttpStatusBasedRetryStrategy(retryStatuses);
    }

    /**
     * Returns {@link CompletableFuture} that contains a {@link Boolean} flag which indicates whether
     * {@link RetryingClient} needs to retry or not.
     * <p>
     * If an {@link Exception} occurs while processing {@link Request} and {@link Response}, this method
     * should not complete the {@link CompletableFuture} with the {@link Exception}. The {@link Exception}
     * needs to be dealt in the {@link RetryStrategy#shouldRetry(Request, Throwable)} method, and
     * the {@link CompletableFuture} has to be completed with {@code false}.
     * </p>
     */
    CompletableFuture<Boolean> shouldRetry(I request, O response);

    /**
     * Returns whether an exception should be retried according to the given request and response.
     */
    default boolean shouldRetry(I request, Throwable thrown) {
        return true;
    }

}
