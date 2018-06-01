/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.internal.HttpHeaderSubscriber;

final class HttpStatusBasedCircuitBreakerStrategy implements CircuitBreakerStrategy<HttpResponse> {

    private final BiFunction<HttpStatus, Throwable, Boolean> function;

    HttpStatusBasedCircuitBreakerStrategy(BiFunction<HttpStatus, Throwable, Boolean> function) {
        this.function = requireNonNull(function, "function");
    }

    @Override
    public CompletionStage<Boolean> shouldReportAsSuccess(HttpResponse response) {
        final CompletableFuture<HttpHeaders> future = new CompletableFuture<>();
        final HttpHeaderSubscriber subscriber = new HttpHeaderSubscriber(future);
        response.completionFuture().whenComplete(subscriber);
        response.subscribe(subscriber);

        return future.handle(
                (headers, thrown) -> function.apply(headers != null ? headers.status() : null, thrown));
    }
}
