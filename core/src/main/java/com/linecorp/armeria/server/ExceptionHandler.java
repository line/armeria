/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.ExceptionHandlerUtil.internalServerErrorResponse;
import static com.linecorp.armeria.server.ExceptionHandlerUtil.serviceUnavailableResponse;

import java.util.function.BiFunction;

import com.linecorp.armeria.common.AggregatedHttpResponse;

/**
 * Converts a {@link Throwable} to an {@link AggregatedHttpResponse}.
 * Use this {@link ExceptionHandler} to send a different response depending on the {@link Throwable}:
 *
 * <pre>{@code
 * ExceptionHandler handler = (ctx, cause) -> {
 *     if (cause instanceof IllegalArgumentException) {
 *         return AggregatedHttpResponse.of(HttpStatus.BAD_REQUEST);
 *     }
 *
 *     // You can return a different response using the path.
 *     if ("/foo".equals(ctx.path())) {
 *         return AggregatedHttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT, "Foo exception");
 *     }
 *
 *     // Delegate to the default handler.
 *     return ExceptionHandler.ofDefault().apply(ctx, cause);
 * }
 *
 * Server.builder().exceptionHandler(handler)...
 *
 * }</pre>
 *
 * @see ServerBuilder#exceptionHandler(ExceptionHandler)
 */
@FunctionalInterface
public interface ExceptionHandler extends BiFunction<ServiceRequestContext, Throwable, AggregatedHttpResponse> {

    /**
     * Returns the default {@link ExceptionHandler}.
     */
    static ExceptionHandler ofDefault() {
        return (ctx, cause) -> {
            // TODO(minwoox): Add more specific conditions such as returning 400 for IllegalArgumentException
            //                when we reach v2.0.
            if (cause instanceof RequestTimeoutException || cause instanceof RequestCancellationException) {
                return serviceUnavailableResponse;
            }
            return internalServerErrorResponse;
        };
    }
}
