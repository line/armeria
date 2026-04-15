/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.common.grpc;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.util.Exceptions;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

public final class InternalGrpcExceptionHandler {

    private final GrpcExceptionHandlerFunction delegate;

    public InternalGrpcExceptionHandler(GrpcExceptionHandlerFunction delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Synchronously handles the specified {@link Throwable}. Used by client-side code paths
     * that run on the event loop and must not block.
     *
     * <p>This method calls {@link GrpcExceptionHandlerFunction#apply} directly. If the delegate
     * is an async-only handler (e.g., created via {@link GrpcExceptionHandlerFunction#ofAsync}),
     * it will throw {@link UnsupportedOperationException}, which is caught and falls back to
     * the default handler.
     */
    public StatusAndMetadata handle(RequestContext ctx, Throwable t) {
        final Throwable peeled = peelAndUnwrap(t);
        Metadata metadata = Status.trailersFromThrowable(peeled);
        if (metadata == null) {
            metadata = new Metadata();
        }
        final Status status = restoreStatus(Status.fromThrowable(peeled), peeled);
        return new StatusAndMetadata(applySyncSafely(ctx, status, peeled, metadata), metadata);
    }

    /**
     * Synchronously handles the specified {@link Throwable} with a pre-extracted {@link Status}.
     * See {@link #handle(RequestContext, Throwable)} for behavior details.
     */
    public Status handle(RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
        final Throwable peeled = peelAndUnwrap(cause);
        final Status restoredStatus = restoreStatus(status, peeled);
        return applySyncSafely(ctx, restoredStatus, peeled, metadata);
    }

    @SuppressWarnings("deprecation")
    private Status applySyncSafely(RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
        try {
            final Status result = delegate.apply(ctx, status, cause, metadata);
            if (result != null) {
                return result;
            }
        } catch (UnsupportedOperationException ignored) {
            // The delegate is an async-only handler (e.g., created via ofAsync).
            // Fall back to the default handler on this synchronous path.
        }
        return applyDefaultHandler(ctx, status, cause, metadata);
    }

    /**
     * Asynchronously handles the specified {@link Throwable} and returns a {@link CompletableFuture}
     * of {@link StatusAndMetadata}.
     */
    @SuppressWarnings("deprecation")
    public CompletableFuture<StatusAndMetadata> handleAsync(RequestContext ctx, Throwable t) {
        final Throwable peeled = peelAndUnwrap(t);
        Metadata metadata = Status.trailersFromThrowable(peeled);
        if (metadata == null) {
            metadata = new Metadata();
        }
        final Status status = restoreStatus(Status.fromThrowable(peeled), peeled);
        final Metadata finalMetadata = metadata;
        return applyAsyncSafely(ctx, status, peeled, metadata)
                       .handle((s, ex) -> {
                           if (ex != null || s == null) {
                               s = applyDefaultHandler(ctx, status, peeled, finalMetadata);
                           }
                           return new StatusAndMetadata(s, finalMetadata);
                       });
    }

    /**
     * Asynchronously handles the specified {@link Throwable} with a pre-extracted {@link Status}
     * and returns a {@link CompletableFuture} of {@link Status}.
     */
    @SuppressWarnings("deprecation")
    public CompletableFuture<Status> handleAsync(RequestContext ctx, Status status,
                                                 Throwable cause, Metadata metadata) {
        final Throwable peeled = peelAndUnwrap(cause);
        final Status restoredStatus = restoreStatus(status, peeled);
        return applyAsyncSafely(ctx, restoredStatus, peeled, metadata)
                       .handle((s, ex) -> {
                           if (ex != null || s == null) {
                               s = applyDefaultHandler(ctx, restoredStatus, peeled, metadata);
                           }
                           return s;
                       });
    }

    private CompletableFuture<Status> applyAsyncSafely(RequestContext ctx, Status status,
                                                       Throwable cause, Metadata metadata) {
        try {
            return requireNonNull(delegate.applyAsync(ctx, status, cause, metadata),
                                  "delegate.applyAsync(...)");
        } catch (Throwable t) {
            final CompletableFuture<Status> future = new CompletableFuture<>();
            future.completeExceptionally(t);
            return future;
        }
    }

    @SuppressWarnings("deprecation")
    private static Status applyDefaultHandler(RequestContext ctx, Status status, Throwable cause,
                                             Metadata metadata) {
        return requireNonNull(GrpcExceptionHandlerFunction.of().apply(ctx, status, cause, metadata),
                              "default grpc exception handler");
    }

    private static Status restoreStatus(Status status, Throwable cause) {
        if (status.getCode() == Code.UNKNOWN) {
            // If ArmeriaStatusException is thrown, it is converted to UNKNOWN and passed through close(Status).
            // So try to restore the original status.
            Status newStatus = null;
            if (cause instanceof StatusRuntimeException) {
                newStatus = ((StatusRuntimeException) cause).getStatus();
            } else if (cause instanceof StatusException) {
                newStatus = ((StatusException) cause).getStatus();
            }
            if (newStatus != null && newStatus.getCode() != Code.UNKNOWN) {
                status = newStatus;
            }
        }
        return status;
    }

    private static Throwable peelAndUnwrap(Throwable t) {
        requireNonNull(t, "t");
        t = Exceptions.peel(t);
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof ArmeriaStatusException) {
                return StatusExceptionConverter.toGrpc((ArmeriaStatusException) cause);
            }
            cause = cause.getCause();
        }
        return t;
    }
}
