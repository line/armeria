/*
 * Copyright 2026 LINE Corporation
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
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.grpc.Metadata;
import io.grpc.Status;

/**
 * An interface that asynchronously converts a {@link Throwable} into a gRPC {@link Status}
 * for gRPC exception handler. This is the async version of {@link GrpcExceptionHandlerFunction}
 * that returns a {@link CompletableFuture} instead of a direct {@link Status}.
 *
 * <p>Use this interface when your exception handling logic requires asynchronous operations
 * such as I/O-bound message lookups (e.g., i18n translation from a remote store).
 *
 * <p>If the returned {@link CompletableFuture} completes with {@code null},
 * the next handler in the chain or the default {@link GrpcExceptionHandlerFunction} will be used.
 *
 * <p>Example usage:
 * <pre>{@code
 * AsyncGrpcExceptionHandlerFunction handler = (ctx, status, cause, metadata) -> {
 *     return i18nService.translateAsync(cause, ctx.locale())
 *                       .thenApply(message -> {
 *                           metadata.put(ERROR_MESSAGE_KEY, message);
 *                           return status;
 *                       });
 * };
 *
 * GrpcService.builder()
 *            .addService(myService)
 *            .asyncExceptionHandler(handler)
 *            .build();
 * }</pre>
 *
 * @see GrpcExceptionHandlerFunction
 */
@UnstableApi
@FunctionalInterface
public interface AsyncGrpcExceptionHandlerFunction {

    /**
     * Maps the specified {@link Throwable} to a gRPC {@link Status} asynchronously
     * and mutates the specified {@link Metadata}.
     * If the returned {@link CompletableFuture} completes with {@code null},
     * the default {@link GrpcExceptionHandlerFunction} will be used as a fallback.
     *
     * <p>The specified {@link Status} parameter was created via {@link Status#fromThrowable(Throwable)}.
     * You can return the {@link Status} or any other {@link Status} as needed.
     *
     * @param ctx the {@link RequestContext} of the current request
     * @param status the {@link Status} created from the {@link Throwable}
     * @param cause the {@link Throwable} that was raised
     * @param metadata the {@link Metadata} to mutate
     * @return a {@link CompletableFuture} that completes with the gRPC {@link Status},
     *         or {@code null} to fall back to the default handler
     */
    CompletableFuture<@Nullable Status> apply(RequestContext ctx, Status status,
                                              Throwable cause, Metadata metadata);

    /**
     * Returns an {@link AsyncGrpcExceptionHandlerFunction} that returns the result of this function
     * when this function returns a non-{@code null} result. When this function returns {@code null},
     * returns the result of the specified {@link AsyncGrpcExceptionHandlerFunction}.
     */
    default AsyncGrpcExceptionHandlerFunction orElse(AsyncGrpcExceptionHandlerFunction next) {
        requireNonNull(next, "next");
        if (this == next) {
            return this;
        }
        return (ctx, status, cause, metadata) ->
                apply(ctx, status, cause, metadata).thenCompose(newStatus -> {
                    if (newStatus != null) {
                        return UnmodifiableFuture.completedFuture(newStatus);
                    }
                    return next.apply(ctx, status, cause, metadata);
                });
    }
}
