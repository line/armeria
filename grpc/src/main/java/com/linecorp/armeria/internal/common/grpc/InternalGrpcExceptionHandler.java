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
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.AsyncGrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.util.Exceptions;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

public final class InternalGrpcExceptionHandler {

    private final GrpcExceptionHandlerFunction syncDelegate;
    @Nullable
    private final AsyncGrpcExceptionHandlerFunction asyncDelegate;

    public InternalGrpcExceptionHandler(GrpcExceptionHandlerFunction delegate) {
        syncDelegate = requireNonNull(delegate, "delegate");
        asyncDelegate = null;
    }

    public InternalGrpcExceptionHandler(AsyncGrpcExceptionHandlerFunction asyncDelegate,
                                        GrpcExceptionHandlerFunction syncFallback) {
        this.asyncDelegate = requireNonNull(asyncDelegate, "asyncDelegate");
        syncDelegate = requireNonNull(syncFallback, "syncFallback");
    }

    /**
     * Returns {@code true} if this handler has an asynchronous delegate.
     */
    public boolean isAsync() {
        return asyncDelegate != null;
    }

    public StatusAndMetadata handle(RequestContext ctx, Throwable t) {
        final Throwable peeled = peelAndUnwrap(t);
        Metadata metadata = Status.trailersFromThrowable(peeled);
        if (metadata == null) {
            metadata = new Metadata();
        }
        Status status = Status.fromThrowable(peeled);
        status = handleSync(ctx, status, peeled, metadata);
        return new StatusAndMetadata(status, metadata);
    }

    public Status handle(RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
        final Throwable peeled = peelAndUnwrap(cause);
        return handleSync(ctx, status, peeled, metadata);
    }

    /**
     * Asynchronously handles the specified {@link Throwable} and returns a {@link CompletableFuture}
     * of {@link StatusAndMetadata}.
     */
    public CompletableFuture<StatusAndMetadata> handleAsync(RequestContext ctx, Throwable t) {
        assert asyncDelegate != null;
        final Throwable peeled = peelAndUnwrap(t);
        Metadata metadata = Status.trailersFromThrowable(peeled);
        if (metadata == null) {
            metadata = new Metadata();
        }
        Status status = Status.fromThrowable(peeled);
        status = restoreStatus(status, peeled);
        final Metadata finalMetadata = metadata;
        final Status finalStatus = status;
        return asyncDelegate.apply(ctx, status, peeled, metadata)
                            .thenApply(s -> {
                                if (s == null) {
                                    // Fall back to the sync delegate.
                                    s = syncDelegate.apply(ctx, finalStatus, peeled, finalMetadata);
                                }
                                assert s != null;
                                return new StatusAndMetadata(s, finalMetadata);
                            });
    }

    /**
     * Asynchronously handles the specified {@link Throwable} with a pre-extracted {@link Status}
     * and returns a {@link CompletableFuture} of {@link Status}.
     */
    public CompletableFuture<Status> handleAsync(RequestContext ctx, Status status,
                                                 Throwable cause, Metadata metadata) {
        assert asyncDelegate != null;
        final Throwable peeled = peelAndUnwrap(cause);
        status = restoreStatus(status, peeled);
        final Status finalStatus = status;
        return asyncDelegate.apply(ctx, status, peeled, metadata)
                            .thenApply(s -> {
                                if (s == null) {
                                    // Fall back to the sync delegate.
                                    s = syncDelegate.apply(ctx, finalStatus, peeled, metadata);
                                }
                                assert s != null;
                                return s;
                            });
    }

    private Status handleSync(RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
        status = restoreStatus(status, cause);
        status = syncDelegate.apply(ctx, status, cause, metadata);
        assert status != null;
        return status;
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
