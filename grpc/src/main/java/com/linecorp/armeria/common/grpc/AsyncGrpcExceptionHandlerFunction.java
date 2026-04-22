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
 * An async variant of {@link GrpcExceptionHandlerFunction} that maps a {@link Throwable} to a gRPC
 * {@link Status} asynchronously.
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
 * <p>Note that client-side exception handling remains synchronous. Async-only handlers are skipped
 * on those paths unless chained with a synchronous fallback via
 * {@link GrpcExceptionHandlerFunction#orElse}.
 */
@UnstableApi
@FunctionalInterface
public interface AsyncGrpcExceptionHandlerFunction {

    /**
     * Asynchronously maps the specified {@link Throwable} to a gRPC {@link Status} and mutates the
     * specified {@link Metadata}. If the returned {@link CompletableFuture} completes with
     * {@code null}, the next handler in the chain or the default
     * {@link GrpcExceptionHandlerFunction} will be used.
     *
     * <p>The specified {@link Status} parameter was created via
     * {@link Status#fromThrowable(Throwable)}. You can return the {@link Status} or any other
     * {@link Status} as needed.
     */
    CompletableFuture<@Nullable Status> applyAsync(RequestContext ctx, Status status, Throwable cause,
                                                   Metadata metadata);

    /**
     * Returns an {@link AsyncGrpcExceptionHandlerFunction} that returns the result of this handler
     * when it completes with a non-{@code null} {@link Status}; otherwise, the specified
     * {@code next} handler is invoked.
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
