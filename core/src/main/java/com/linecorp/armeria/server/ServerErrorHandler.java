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
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;

/**
 * Provides the error responses in case of unexpected exceptions or protocol errors.
 * Implement this interface to customize Armeria's error responses.
 *
 * <pre>{@code
 * ServerErrorHandler errorHandler = (ctx, cause) -> {
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
 *     // Return null to let ServerErrorHandler.ofDefault() handle the exception.
 *     return null;
 * }
 *
 * Server.builder().errorHandler(errorHandler)...
 * }</pre>
 *
 * <h2>Recording a service exception (or not)</h2>
 *
 * <p>By default, an exception raised by a service or a decorator is captured and recorded into
 * {@link RequestLog#responseCause()}. You can keep Armeria from recording it while sending the
 * desired response by returning a failed response whose cause is an {@link HttpStatusException} or
 * {@link HttpResponseException}:
 * <pre>{@code
 * ServerErrorHandler errorHandler = (ctx, cause) -> {
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
 * @see ServerBuilder#errorHandler(ServerErrorHandler)
 * @see ServiceErrorHandler
 */
@UnstableApi
@FunctionalInterface
public interface ServerErrorHandler {

    /**
     * Returns the default {@link ServerErrorHandler}. This handler is also used as the final fallback when
     * the handler customized with {@link ServerBuilder#errorHandler(ServerErrorHandler)} returns {@code null}.
     * For example, the following handler basically delegates all error handling to the default handler:
     * <pre>{@code
     * Server
     *   .builder()
     *   .errorHandler((ctx, cause) -> null)
     *   ...
     * }</pre>
     */
    static ServerErrorHandler ofDefault() {
        return DefaultServerErrorHandler.INSTANCE;
    }

    /**
     * Returns an {@link HttpResponse} for the given {@link Throwable} raised by a service.
     * This method is invoked once for each request failed with a service-level exception.
     *
     * @param ctx the {@link ServiceRequestContext} of the current request.
     * @param cause the {@link Throwable} raised by the service that handled the current request.
     *
     * @return an {@link HttpResponse} to send to the client, or {@code null} to let the next handler
     *         specified with {@link #orElse(ServerErrorHandler)} handle the event.
     */
    @Nullable
    HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause);

    /**
     * Returns an {@link AggregatedHttpResponse} for the protocol error signified by the given
     * {@link HttpStatus}, {@code message}, and {@link Throwable}. This method is invoked once
     * for each request failed at the protocol level.
     *
     * @param config the {@link ServiceConfig} that provides the configuration properties.
     * @param headers the received {@link RequestHeaders}, or {@code null} in case of severe protocol violation.
     * @param status the desired {@link HttpStatus} of the error response.
     * @param description an optional human-readable description of the error.
     * @param cause an optional exception that may contain additional information about the error, such as
     *              {@link ContentTooLargeException}.
     *
     * @return an {@link AggregatedHttpResponse} to send to the client, or {@code null} to let the next handler
     *         specified with {@link #orElse(ServerErrorHandler)} handle the event.
     */
    @Nullable
    default AggregatedHttpResponse onProtocolViolation(ServiceConfig config,
                                                       @Nullable RequestHeaders headers,
                                                       HttpStatus status,
                                                       @Nullable String description,
                                                       @Nullable Throwable cause) {
        return renderStatus(null, config, headers, status, description, cause);
    }

    /**
     * Returns an {@link AggregatedHttpResponse} generated from the given {@link HttpStatus}, {@code message}
     * and {@link Throwable}. When {@code null} is returned, the next {@link ServerErrorHandler}
     * in the invocation chain will be used as a fallback (See {@link #orElse(ServerErrorHandler)}
     * for more information).
     *
     * <p>Note: This method can be invoked by Armeria in combination with the other methods in
     * {@link ServerErrorHandler} or even independently, and thus should not be used for counting
     * {@link HttpStatusException}s or collecting stats. Use
     * {@link #onServiceException(ServiceRequestContext, Throwable)} and
     * {@link #onProtocolViolation(ServiceConfig, RequestHeaders, HttpStatus, String, Throwable)} instead.
     *
     * @param ctx the {@link ServiceRequestContext} of the request being handled, or {@code null} in case of
     *            severe protocol violation.
     * @param config the {@link ServiceConfig} that provides the configuration properties.
     * @param headers the received {@link RequestHeaders}, or {@code null} in case of severe protocol violation.
     * @param status the desired {@link HttpStatus} of the error response.
     * @param description an optional human-readable description of the error.
     * @param cause an optional exception that may contain additional information about the error, such as
     *              {@link ContentTooLargeException}.
     *
     * @return an {@link AggregatedHttpResponse}, or {@code null} to let the next handler specified with
     *         {@link #orElse(ServerErrorHandler)} handle the event.
     */
    @Nullable
    default AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                                ServiceConfig config,
                                                @Nullable RequestHeaders headers,
                                                HttpStatus status,
                                                @Nullable String description,
                                                @Nullable Throwable cause) {
        return null;
    }

    /**
     * Returns a newly created {@link ServerErrorHandler} that tries this {@link ServerErrorHandler} first and
     * then the specified {@link ServerErrorHandler} when the first call returns {@code null}.
     * <pre>{@code
     * ServerErrorHandler handler = (ctx, cause) -> {
     *     if (cause instanceof FirstException) {
     *         return HttpResponse.of(200);
     *     }
     *     return null;
     * }
     * assert handler.onServiceException(ctx, new FirstException()) != null;
     * assert handler.onServiceException(ctx, new SecondException()) == null;
     * assert handler.onServiceException(ctx, new ThirdException()) == null;
     *
     * ServerErrorHandler combinedHandler = handler.orElse((ctx, cause) -> {
     *     if (cause instanceof SecondException) {
     *         return HttpResponse.of(200);
     *     }
     *     return null;
     * });
     *
     * assert handler.onServiceException(ctx, new FirstException()) != null;
     * assert handler.onServiceException(ctx, new SecondException()) != null;
     * assert handler.onServiceException(ctx, new ThirdException()) == null;
     *
     * // The default handler never returns null.
     * ServerErrorHandler nonNullHandler = combinedHandler.orElse(ServerErrorHandler.ofDefault());
     * assert handler.onServiceException(ctx, new FirstException()) != null;
     * assert handler.onServiceException(ctx, new SecondException()) != null;
     * assert handler.onServiceException(ctx, new ThirdException()) != null;
     * }</pre>
     */
    default ServerErrorHandler orElse(ServerErrorHandler other) {
        requireNonNull(other, "other");
        return new ServerErrorHandler() {
            @Nullable
            @Override
            public HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
                final HttpResponse response = ServerErrorHandler.this.onServiceException(ctx, cause);
                if (response != null) {
                    return response;
                }
                return other.onServiceException(ctx, cause);
            }

            @Nullable
            @Override
            public AggregatedHttpResponse onProtocolViolation(ServiceConfig config,
                                                              @Nullable RequestHeaders headers,
                                                              HttpStatus status,
                                                              @Nullable String description,
                                                              @Nullable Throwable cause) {
                final AggregatedHttpResponse response =
                        ServerErrorHandler.this.onProtocolViolation(
                                config, headers, status, description, cause);
                if (response != null) {
                    return response;
                }
                return other.onProtocolViolation(config, headers, status, description, cause);
            }

            @Nullable
            @Override
            public AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                                       ServiceConfig config,
                                                       @Nullable RequestHeaders headers,
                                                       HttpStatus status,
                                                       @Nullable String description,
                                                       @Nullable Throwable cause) {
                final AggregatedHttpResponse response =
                        ServerErrorHandler.this.renderStatus(ctx, config, headers, status, description, cause);
                if (response != null) {
                    return response;
                }
                return other.renderStatus(ctx, config, headers, status, description, cause);
            }
        };
    }

    /**
     * Transforms this {@link ServerErrorHandler} into a {@link ServiceErrorHandler}.
     */
    default ServiceErrorHandler asServiceErrorHandler() {
        return new ServiceErrorHandler() {
            @Override
            public @Nullable HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
                return ServerErrorHandler.this.onServiceException(ctx, cause);
            }

            @Override
            public @Nullable AggregatedHttpResponse renderStatus(ServiceRequestContext ctx,
                                                                 RequestHeaders headers,
                                                                 HttpStatus status,
                                                                 @Nullable String description,
                                                                 @Nullable Throwable cause) {
                return ServerErrorHandler.this.renderStatus(ctx, ctx.config(), headers,
                                                            status, description, cause);
            }
        };
    }
}
