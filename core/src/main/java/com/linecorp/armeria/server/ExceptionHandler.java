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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Converts a {@link Throwable} to an {@link AggregatedHttpResponse}.
 * Use this {@link ExceptionHandler} to send a different response depending on the {@link Throwable}:
 *
 * <pre>{@code
 * ExceptionHandler handler = (ctx, cause) -> {
 *     if (cause instanceof IllegalArgumentException) {
 *         return HttpResponse.of(HttpStatus.BAD_REQUEST);
 *     }
 *
 *     // You can return a different response using the path.
 *     if ("/outage".equals(ctx.path())) {
 *         return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
 *                                MediaType.PLAIN_TEXT, "Oops, something went wrong.");
 *     }
 *
 *     // Return null to let ExceptionHandler.ofDefault() convert the exception.
 *     return null;
 * }
 *
 * Server.builder().exceptionHandler(handler)...
 *
 * }</pre>
 *
 * @see ServerBuilder#exceptionHandler(ExceptionHandler)
 */
@UnstableApi
@FunctionalInterface
public interface ExceptionHandler {

    /**
     * Returns the default {@link ExceptionHandler}.
     */
    static ExceptionHandler ofDefault() {
        return DefaultExceptionHandler.INSTANCE;
    }

    /**
     * Converts the given {@link Throwable} to an {@link AggregatedHttpResponse}. When {@code null} is returned,
     * {@link ExceptionHandler#ofDefault()} converts the {@link Throwable}.
     *
     * @see #orElse(ExceptionHandler)
     */
    @Nullable
    HttpResponse convert(ServiceRequestContext context, Throwable cause);

    /**
     * Creates a new {@link ExceptionHandler} that tries this {@link ExceptionHandler} first and then the
     * specified {@link ExceptionHandler} when this {@link ExceptionHandler} returns {@code null}.
     */
    default ExceptionHandler orElse(ExceptionHandler other) {
        requireNonNull(other, "other");
        return (ctx, cause) -> {
            @Nullable
            final HttpResponse response = convert(ctx, cause);
            if (response != null) {
                return response;
            }
            return other.convert(ctx, cause);
        };
    }
}
