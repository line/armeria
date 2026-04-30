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
 * An async-only variant of {@link GrpcExceptionHandlerFunction} that maps a {@link Throwable} to a
 * gRPC {@link Status} asynchronously. Only {@link #applyAsync} needs to be implemented.
 *
 * <p>Use this interface when your exception handling logic is inherently asynchronous (e.g., i18n
 * translation from a remote store) and you want to pass a lambda directly to
 * {@code GrpcServiceBuilder.asyncExceptionHandler(AsyncGrpcExceptionHandlerFunction)}.
 *
 * <p>Example usage:
 * <pre>{@code
 * GrpcService.builder()
 *            .addService(myService)
 *            .asyncExceptionHandler((ctx, status, cause, metadata) ->
 *                    i18nService.translateAsync(cause, locale)
 *                               .thenApply(message -> {
 *                                   metadata.put(ERROR_MESSAGE_KEY, message);
 *                                   return status;
 *                               }))
 *            .build();
 * }</pre>
 *
 * <p>This interface is async-only. The inherited synchronous {@link #apply} method throws
 * {@link UnsupportedOperationException} by default; implement {@link GrpcExceptionHandlerFunction}
 * directly if you need a synchronous handler.
 */
// Intentional SAM change: applyAsync is the abstract method here; apply is unsupported.
@SuppressWarnings("FunctionalInterfaceMethodChanged")
@UnstableApi
@FunctionalInterface
public interface AsyncGrpcExceptionHandlerFunction extends GrpcExceptionHandlerFunction {

    /**
     * Throws {@link UnsupportedOperationException} because this interface is async-only; use
     * {@link #applyAsync} instead. Implement {@link GrpcExceptionHandlerFunction} directly if you
     * need a synchronous handler.
     *
     * @deprecated Use {@link #applyAsync(RequestContext, Status, Throwable, Metadata)} instead.
     */
    @Override
    @Deprecated
    default @Nullable Status apply(RequestContext ctx, Status status, Throwable cause,
                                   Metadata metadata) {
        throw new UnsupportedOperationException(
                "apply() is not supported on AsyncGrpcExceptionHandlerFunction; use applyAsync(...) " +
                "instead. Implement GrpcExceptionHandlerFunction directly for synchronous handlers.");
    }

    /**
     * Asynchronously maps the specified {@link Throwable} to a gRPC {@link Status} and mutates the
     * specified {@link Metadata}. If the returned {@link CompletableFuture} completes with
     * {@code null}, the next handler in the chain or the default
     * {@link GrpcExceptionHandlerFunction} will be used.
     */
    @Override
    CompletableFuture<@Nullable Status> applyAsync(RequestContext ctx, Status status, Throwable cause,
                                                   Metadata metadata);

    /**
     * Returns an {@link AsyncGrpcExceptionHandlerFunction} that invokes this handler first and
     * falls back to the specified {@code next} handler when this handler completes with
     * {@code null}.
     *
     * <p>This overload preserves the async type so that chained async handlers can still be passed
     * directly to
     * {@code GrpcServiceBuilder#asyncExceptionHandler(AsyncGrpcExceptionHandlerFunction)} or
     * {@code GrpcClientBuilder#asyncExceptionHandler(AsyncGrpcExceptionHandlerFunction)}.
     */
    default AsyncGrpcExceptionHandlerFunction orElse(AsyncGrpcExceptionHandlerFunction next) {
        requireNonNull(next, "next");
        if (this == next) {
            return this;
        }
        return (ctx, status, cause, metadata) ->
                applyAsync(ctx, status, cause, metadata).thenCompose(newStatus -> {
                    if (newStatus != null) {
                        return UnmodifiableFuture.completedFuture(newStatus);
                    }
                    return next.applyAsync(ctx, status, cause, metadata);
                });
    }
}
