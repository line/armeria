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

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.HttpHeaderSubscriber;

/**
 * Provides a {@link RetryStrategy} that decides to retry the request based on the {@link HttpStatus} of
 * its response.
 */
final class HttpStatusBasedRetryStrategy implements RetryStrategy<HttpRequest, HttpResponse> {

    private final Function<HttpStatus, Optional<Backoff>> statusBasedBackoffFunction;

    /**
     * Creates a new instance.
     */
    HttpStatusBasedRetryStrategy(Function<HttpStatus, Optional<Backoff>> statusBasedBackoffFunction) {
        this.statusBasedBackoffFunction = requireNonNull(
                statusBasedBackoffFunction, "statusBasedBackoffFunction");
    }

    @Override
    public CompletableFuture<Optional<Backoff>> shouldRetry(HttpRequest request, HttpResponse response) {
        final CompletableFuture<HttpHeaders> future = new CompletableFuture<>();
        final HttpHeaderSubscriber subscriber = new HttpHeaderSubscriber(future);
        response.completionFuture().whenComplete(subscriber);
        response.subscribe(subscriber);

        return future.handle((headers, unused) -> {
            if (headers != null) {
                final HttpStatus resStatus = headers.status();
                if (resStatus != null) {
                    return statusBasedBackoffFunction.apply(resStatus);
                }
            }
            return Optional.empty();
        });
    }
}
