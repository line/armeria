/*
 * Copyright 2023 LINE Corporation
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
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;

/**
 * Provides the error responses in case of unexpected exceptions.
 * Implement this interface to customize Armeria's error responses.
 *
 * <pre>{@code
 * ServiceErrorHandler errorHandler = (ctx, cause) -> {
 *     if (cause instanceof IllegalArgumentException) {
 *         return HttpResponse.of(HttpStatus.BAD_REQUEST);
 *     }
 *
 *     // Return null to let ServerErrorHandler.ofDefault() handle the exception.
 *     return null;
 * }
 *
 * Server.builder().route().errorHandler(errorHandler)...
 * }</pre>
 *
 * <h2>Recording a service exception (or not)</h2>
 *
 * <p>By default, an exception raised by a service or a decorator is captured and recorded into
 * {@link RequestLog#responseCause()}. You can keep Armeria from recording it while sending the
 * desired response by returning a failed response whose cause is an {@link HttpStatusException} or
 * {@link HttpResponseException}:
 * <pre>{@code
 * ServiceErrorHandler errorHandler = (ctx, cause) -> {
 *     if (cause instanceof IllegalArgumentException) {
 *         // IllegalArgumentException is captured into RequestLog#responseCause().
 *         return HttpResponse.of(HttpStatus.BAD_REQUEST);
 *     }
 *
 *     if (cause instanceof NotFoundException) {
 *         // NotFoundException is NOT captured into RequestLog#responseCause().
 *         return HttpResponse.ofFailure(HttpStatusException.of(HttpStatus.NOT_FOUND));
 *     }
 *     ...
 * }
 * }</pre>
 *
 * @see ServerErrorHandler
 */
@UnstableApi
@FunctionalInterface
public interface ServiceErrorHandler {
    /**
     * Returns an {@link HttpResponse} for the given {@link Throwable} raised by a service.
     * This method is invoked once for each request failed with a service-level exception.
     *
     * @param ctx the {@link ServiceRequestContext} of the current request.
     * @param cause the {@link Throwable} raised by the service that handled the current request.
     *
     * @return an {@link HttpResponse} to send to the client, or {@code null} to let the next handler
     *         specified with {@link #orElse(ServiceErrorHandler)} handle the event.
     */
    @Nullable
    HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause);

    /**
     * Returns an {@link AggregatedHttpResponse} generated from the given {@link HttpStatus}, {@code message}
     * and {@link Throwable}. When {@code null} is returned, the next {@link ServiceErrorHandler}
     * in the invocation chain will be used as a fallback (See {@link #orElse(ServiceErrorHandler)}
     * for more information).
     *
     * @param ctx the {@link ServiceRequestContext} of the request being handled.
     * @param headers the received {@link RequestHeaders}.
     * @param status the desired {@link HttpStatus} of the error response.
     * @param description an optional human-readable description of the error.
     * @param cause an optional exception that may contain additional information about the error, such as
     *              {@link IllegalArgumentException}.
     *
     * @return an {@link AggregatedHttpResponse}, or {@code null} to let the next handler specified with
     *         {@link #orElse(ServiceErrorHandler)} handle the event.
     */
    @Nullable
    default AggregatedHttpResponse renderStatus(ServiceRequestContext ctx,
                                                RequestHeaders headers,
                                                HttpStatus status,
                                                @Nullable String description,
                                                @Nullable Throwable cause) {
        return null;
    }

    /**
     * Returns a newly created {@link ServiceErrorHandler} that tries this {@link ServiceErrorHandler} first and
     * then the specified {@link ServiceErrorHandler} when the first call returns {@code null}.
     *
     * <pre>{@code
     * ServiceErrorHandler handler = (ctx, cause) -> {
     *     if (cause instanceof FirstException) {
     *         return HttpResponse.of(200);
     *     }
     *     return null;
     * }
     * assert handler.onServiceException(ctx, new FirstException()) != null;
     * assert handler.onServiceException(ctx, new SecondException()) == null;
     * assert handler.onServiceException(ctx, new ThirdException()) == null;
     *
     * ServiceErrorHandler combinedHandler = handler.orElse((ctx, cause) -> {
     *     if (cause instanceof SecondException) {
     *         return HttpResponse.of(200);
     *     }
     *     return null;
     * });
     *
     * assert combinedHandler.onServiceException(ctx, new FirstException()) != null;
     * assert combinedHandler.onServiceException(ctx, new SecondException()) != null;
     * assert combinedHandler.onServiceException(ctx, new ThirdException()) == null;
     *
     * // The default handler never returns null.
     * ServiceErrorHandler nonNullHandler = combinedHandler.orElse(ServiceErrorHandler.ofDefault());
     * assert nonNullHandler.onServiceException(ctx, new FirstException()) != null;
     * assert nonNullHandler.onServiceException(ctx, new SecondException()) != null;
     * assert nonNullHandler.onServiceException(ctx, new ThirdException()) != null;
     * }</pre>
     */
    default ServiceErrorHandler orElse(ServiceErrorHandler other) {
        requireNonNull(other, "other");
        return (ctx, cause) -> {
            final HttpResponse response = onServiceException(ctx, cause);
            if (response != null) {
                return response;
            }
            return other.onServiceException(ctx, cause);
        };
    }
}
