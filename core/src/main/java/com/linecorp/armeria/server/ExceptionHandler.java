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
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.UnstableApi;

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
        return (ctx, cause) -> {
            // TODO(minwoox): Add more specific conditions such as returning 400 for IllegalArgumentException
            //                when we reach v2.0.
            if (cause instanceof HttpStatusException) {
                final HttpStatus httpStatus = ((HttpStatusException) cause).httpStatus();
                return AggregatedHttpResponse.of(httpStatus, MediaType.PLAIN_TEXT_UTF_8,
                                                 httpStatus.toHttpData());
            }
            if (cause instanceof RequestTimeoutException || cause instanceof RequestCancellationException) {
                return serviceUnavailableResponse;
            }
            return internalServerErrorResponse;
        };
    }

    /**
     * Converts the given {@link Throwable} to an {@link AggregatedHttpResponse}.
     */
    @Nullable
    AggregatedHttpResponse convert(ServiceRequestContext context, Throwable cause);

    /**
     * Creates a new {@link ExceptionHandler} that tries this {@link ExceptionHandler} first and then the
     * specified {@link ExceptionHandler} when this {@link ExceptionHandler} returns {@code null}.
     */
    default ExceptionHandler orElse(ExceptionHandler other) {
        requireNonNull(other, "other");
        return (ctx, cause) -> {
            final AggregatedHttpResponse response = convert(ctx, cause);
            if (response != null) {
                return response;
            }
            return other.convert(ctx, cause);
        };
    }
}
