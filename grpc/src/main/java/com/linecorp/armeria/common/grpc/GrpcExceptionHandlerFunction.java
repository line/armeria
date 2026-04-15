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

package com.linecorp.armeria.common.grpc;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.grpc.Metadata;
import io.grpc.Status;

/**
 * An interface that converts a {@link Throwable} into a gRPC {@link Status} for gRPC exception handler.
 */
@UnstableApi
@FunctionalInterface
public interface GrpcExceptionHandlerFunction {

    /**
     * Returns a newly created {@link GrpcExceptionHandlerFunctionBuilder}.
     */
    static GrpcExceptionHandlerFunctionBuilder builder() {
        return new GrpcExceptionHandlerFunctionBuilder();
    }

    /**
     * Returns the default {@link GrpcExceptionHandlerFunction}.
     */
    @UnstableApi
    static GrpcExceptionHandlerFunction of() {
        return DefaultGrpcExceptionHandlerFunction.INSTANCE;
    }

    /**
     * Returns a new {@link GrpcExceptionHandlerFunction} that only supports asynchronous
     * exception handling.
     *
     * <p>The returned handler's synchronous {@link #apply} method always returns {@code null}
     * so synchronous call paths can continue to the next handler in the chain or fall back to
     * the default handler. Server-side call paths use {@link #applyAsync}; client-side exception
     * handling remains synchronous and therefore requires a synchronous fallback if you need
     * custom client-side status mapping.
     *
     * <p>Use this factory when your exception handling logic is inherently asynchronous
     * (e.g., i18n translation from a remote store) and you don't need a synchronous variant.
     *
     * <p>Example usage:
     * <pre>{@code
     * GrpcService.builder()
     *            .addService(myService)
     *            .exceptionHandler(GrpcExceptionHandlerFunction.ofAsync(
     *                    (ctx, status, cause, metadata) ->
     *                            i18nService.translateAsync(cause, locale)
     *                                       .thenApply(message -> {
     *                                           metadata.put(ERROR_MESSAGE_KEY, message);
     *                                           return status;
     *                                       })))
     *            .build();
     * }</pre>
     */
    @UnstableApi
    static GrpcExceptionHandlerFunction ofAsync(AsyncHandler asyncHandler) {
        requireNonNull(asyncHandler, "asyncHandler");
        return new GrpcExceptionHandlerFunction() {
            @Override
            public @Nullable Status apply(RequestContext ctx, Status status, Throwable cause,
                                          Metadata metadata) {
                return null;
            }

            @Override
            public CompletableFuture<@Nullable Status> applyAsync(RequestContext ctx, Status status,
                                                                   Throwable cause, Metadata metadata) {
                try {
                    return requireNonNull(asyncHandler.apply(ctx, status, cause, metadata),
                                          "asyncHandler.apply(...)");
                } catch (Throwable t) {
                    final CompletableFuture<@Nullable Status> future = new CompletableFuture<>();
                    future.completeExceptionally(t);
                    return future;
                }
            }
        };
    }

    /**
     * A functional interface used by {@link #ofAsync(AsyncHandler)} for creating
     * async-only exception handlers with lambda syntax.
     */
    @UnstableApi
    @FunctionalInterface
    interface AsyncHandler {
        /**
         * Asynchronously maps the specified {@link Throwable} to a gRPC {@link Status}
         * and mutates the specified {@link Metadata}.
         */
        CompletableFuture<@Nullable Status> apply(RequestContext ctx, Status status,
                                                  Throwable cause, Metadata metadata);
    }

    /**
     * Maps the specified {@link Throwable} to a gRPC {@link Status} synchronously
     * and mutates the specified {@link Metadata}.
     * If {@code null} is returned, {@link #of()} will be used to return {@link Status} as the default.
     *
     * <p>The specified {@link Status} parameter was created via {@link Status#fromThrowable(Throwable)}.
     * You can return the {@link Status} or any other {@link Status} as needed.
     *
     * @deprecated Override {@link #applyAsync(RequestContext, Status, Throwable, Metadata)} instead.
     */
    @Deprecated
    @Nullable
    Status apply(RequestContext ctx, Status status, Throwable cause, Metadata metadata);

    /**
     * Maps the specified {@link Throwable} to a gRPC {@link Status} asynchronously
     * and mutates the specified {@link Metadata}.
     * If the returned {@link CompletableFuture} completes with {@code null},
     * the next handler in the chain or the default {@link GrpcExceptionHandlerFunction} will be used.
     *
     * <p>The default implementation delegates to {@link #apply(RequestContext, Status, Throwable, Metadata)}.
     *
     * <p>Override this method when your exception handling logic requires asynchronous operations
     * such as I/O-bound message lookups (e.g., i18n translation from a remote store).
     * For async-only handlers, consider using {@link #ofAsync(AsyncHandler)} instead.
     *
     * <p>Note that client-side exception handling remains synchronous. Async-only handlers are
     * skipped on those paths unless chained with a synchronous fallback using {@link #orElse}.
     */
    @UnstableApi
    @SuppressWarnings("deprecation")
    default CompletableFuture<@Nullable Status> applyAsync(RequestContext ctx, Status status,
                                                           Throwable cause, Metadata metadata) {
        try {
            return CompletableFuture.completedFuture(apply(ctx, status, cause, metadata));
        } catch (Throwable t) {
            final CompletableFuture<@Nullable Status> future = new CompletableFuture<>();
            future.completeExceptionally(t);
            return future;
        }
    }

    /**
     * Returns a {@link GrpcExceptionHandlerFunction} that returns the result of this function
     * when this function returns non {@code null} result, in which case the specified function isn't executed.
     * when this function returns {@code null}, returns a {@link GrpcExceptionHandlerFunction} that the result
     * of the specified {@link GrpcExceptionHandlerFunction}.
     */
    default GrpcExceptionHandlerFunction orElse(GrpcExceptionHandlerFunction next) {
        requireNonNull(next, "next");
        if (this == next) {
            return this;
        }
        return new GrpcExceptionHandlerFunction() {

            @Override
            @SuppressWarnings("deprecation")
            public @Nullable Status apply(RequestContext ctx, Status status, Throwable cause,
                                          Metadata metadata) {
                final Status newStatus =
                        GrpcExceptionHandlerFunction.this.apply(ctx, status, cause, metadata);
                if (newStatus != null) {
                    return newStatus;
                }
                return next.apply(ctx, status, cause, metadata);
            }

            @Override
            public CompletableFuture<@Nullable Status> applyAsync(RequestContext ctx, Status status,
                                                                   Throwable cause,
                                                                   Metadata metadata) {
                return GrpcExceptionHandlerFunction.this.applyAsync(ctx, status, cause, metadata)
                                                        .thenCompose(newStatus -> {
                                                            if (newStatus != null) {
                                                                return CompletableFuture.completedFuture(
                                                                        newStatus);
                                                            }
                                                            return next.applyAsync(ctx, status, cause,
                                                                                   metadata);
                                                        });
            }
        };
    }
}
