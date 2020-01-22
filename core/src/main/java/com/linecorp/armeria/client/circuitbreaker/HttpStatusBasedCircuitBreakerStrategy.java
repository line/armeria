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

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLogProperty;

final class HttpStatusBasedCircuitBreakerStrategy implements CircuitBreakerStrategy {

    private final BiFunction<HttpStatus, Throwable, Boolean> function;

    HttpStatusBasedCircuitBreakerStrategy(BiFunction<HttpStatus, Throwable, Boolean> function) {
        this.function = requireNonNull(function, "function");
    }

    @Override
    public CompletionStage<Boolean> shouldReportAsSuccess(ClientRequestContext ctx, @Nullable Throwable cause) {
        if (cause instanceof UnprocessedRequestException) {
            return CompletableFuture.completedFuture(null); // Neither success nor failure.
        }
        final HttpStatus status;
        if (ctx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            status = ctx.log().partial().responseHeaders().status();
        } else {
            status = null;
        }
        return CompletableFuture.completedFuture(function.apply(status, cause));
    }
}
