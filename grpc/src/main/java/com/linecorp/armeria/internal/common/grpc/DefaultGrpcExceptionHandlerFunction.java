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

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.circuitbreaker.FailFastException;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.common.grpc.StackTraceElementProto;
import com.linecorp.armeria.common.grpc.StatusCauseException;
import com.linecorp.armeria.common.grpc.ThrowableProto;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;

public final class DefaultGrpcExceptionHandlerFunction implements GrpcExceptionHandlerFunction {
    private static final GrpcExceptionHandlerFunction INSTANCE = new DefaultGrpcExceptionHandlerFunction();

    /**
     * Returns the default {@link GrpcExceptionHandlerFunction}. This handler is also used as the final
     * fallback when the handler customized
     * with either {@link GrpcClientBuilder#exceptionHandler(GrpcExceptionHandlerFunction)}
     * or {@link GrpcServiceBuilder#exceptionHandler(GrpcExceptionHandlerFunction)} returns {@code null}.
     * For example, the following handler basically delegates all error handling to the default handler:
     * <pre>{@code
     * // For GrpcClient
     * GrpcClients
     *   .builder("http://foo.com")
     *   .exceptionHandler((ctx, cause, metadata) -> null)
     *   ...
     *
     * // For GrpcServer
     * GrpcService
     *   .builder()
     *   .exceptionHandler((ctx, cause, metadata) -> null)
     *   ...
     * }</pre>
     */
    public static GrpcExceptionHandlerFunction ofDefault() {
        return DefaultGrpcExceptionHandlerFunction.INSTANCE;
    }

    @Override
    public @Nullable Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
        return fromThrowable(cause);
    }

    /**
     * Converts the {@link Throwable} to a {@link Status}, taking into account exceptions specific to Armeria as
     * well and the protocol package.
     */
    public static Status fromThrowable(Throwable t) {
        t = peelAndUnwrap(requireNonNull(t, "t"));
        return statusFromThrowable(t);
    }

    /**
     * Converts the {@link Throwable} to a {@link Status}.
     * If the specified {@code statusFunction} returns {@code null},
     * the built-in exception mapping rule, which takes into account exceptions specific to Armeria as well
     * and the protocol package, is used by default.
     */
    public static Status fromThrowable(@Nullable GrpcStatusFunction statusFunction, RequestContext ctx,
                                       Throwable t, Metadata metadata) {
        final GrpcExceptionHandlerFunction exceptionHandler =
                statusFunction != null ? statusFunction::apply : null;
        return fromThrowable(exceptionHandler, ctx, t, metadata);
    }

    /**
     * Converts the {@link Throwable} to a {@link Status}.
     * If the specified {@link GrpcExceptionHandlerFunction} returns {@code null},
     * the built-in exception mapping rule, which takes into account exceptions specific to Armeria as well
     * and the protocol package, is used by default.
     */
    public static Status fromThrowable(@Nullable GrpcExceptionHandlerFunction exceptionHandler,
                                       RequestContext ctx, Throwable t, Metadata metadata) {
        t = peelAndUnwrap(requireNonNull(t, "t"));

        if (exceptionHandler != null) {
            final Status status = exceptionHandler.apply(ctx, t, metadata);
            if (status != null) {
                return status;
            }
        }

        return statusFromThrowable(t);
    }

    private static Status statusFromThrowable(Throwable t) {
        final Status s = Status.fromThrowable(t);
        if (s.getCode() != Code.UNKNOWN) {
            return s;
        }

        if (t instanceof ClosedSessionException || t instanceof ClosedChannelException) {
            // ClosedChannelException is used any time the Netty channel is closed. Proper error
            // processing requires remembering the error that occurred before this one and using it
            // instead.
            return s;
        }
        if (t instanceof ClosedStreamException || t instanceof RequestTimeoutException) {
            return Status.CANCELLED.withCause(t);
        }
        if (t instanceof InvalidProtocolBufferException) {
            return Status.INVALID_ARGUMENT.withCause(t);
        }
        if (t instanceof UnprocessedRequestException ||
            t instanceof IOException ||
            t instanceof FailFastException) {
            return Status.UNAVAILABLE.withCause(t);
        }
        if (t instanceof Http2Exception) {
            if (t instanceof Http2Exception.StreamException &&
                ((Http2Exception.StreamException) t).error() == Http2Error.CANCEL) {
                return Status.CANCELLED;
            }
            return Status.INTERNAL.withCause(t);
        }
        if (t instanceof TimeoutException) {
            return Status.DEADLINE_EXCEEDED.withCause(t);
        }
        if (t instanceof ContentTooLargeException) {
            return Status.RESOURCE_EXHAUSTED.withCause(t);
        }
        return s;
    }

    /**
     * Converts the specified {@link Status} to a new user-specified {@link Status}
     * using the specified {@link GrpcStatusFunction}.
     * Returns the given {@link Status} as is if the {@link GrpcStatusFunction} returns {@code null}.
     */
    public static Status fromStatusFunction(@Nullable GrpcStatusFunction statusFunction,
                                            RequestContext ctx, Status status, Metadata metadata) {
        final GrpcExceptionHandlerFunction exceptionHandler =
                statusFunction != null ? statusFunction::apply : null;
        return fromExceptionHandler(exceptionHandler, ctx, status, metadata);
    }

    /**
     * Converts the specified {@link Status} to a new user-specified {@link Status}
     * using the specified {@link GrpcExceptionHandlerFunction}.
     * Returns the given {@link Status} as is if the {@link GrpcExceptionHandlerFunction} returns {@code null}.
     */
    public static Status fromExceptionHandler(@Nullable GrpcExceptionHandlerFunction exceptionHandler,
                                              RequestContext ctx, Status status, Metadata metadata) {
        requireNonNull(status, "status");

        if (exceptionHandler != null) {
            final Throwable cause = status.getCause();
            if (cause != null) {
                final Throwable unwrapped = peelAndUnwrap(cause);
                final Status newStatus = exceptionHandler.apply(ctx, unwrapped, metadata);
                if (newStatus != null) {
                    return newStatus;
                }
            }
        }
        return status;
    }

    private static Throwable peelAndUnwrap(Throwable t) {
        t = Exceptions.peel(t);
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof ArmeriaStatusException) {
                t = StatusExceptionConverter.toGrpc((ArmeriaStatusException) cause);
                break;
            }
            cause = cause.getCause();
        }
        return t;
    }

    /**
     * Fills the information from the {@link Throwable} into a {@link ThrowableProto} for
     * returning to a client.
     */
    public static ThrowableProto serializeThrowable(Throwable t) {
        final ThrowableProto.Builder builder = ThrowableProto.newBuilder();

        if (t instanceof StatusCauseException) {
            final StatusCauseException statusCause = (StatusCauseException) t;
            builder.setOriginalClassName(statusCause.getOriginalClassName());
            builder.setOriginalMessage(statusCause.getOriginalMessage());
        } else {
            builder.setOriginalClassName(t.getClass().getCanonicalName());
            builder.setOriginalMessage(Strings.nullToEmpty(t.getMessage()));
        }

        // In order not to exceed max headers size, max stack trace elements is limited to 10
        final StackTraceElement[] stackTraceElements = t.getStackTrace();
        // TODO(ikhoon): Provide a way to configure maxStackTraceElements
        final int maxStackTraceElements = Math.min(10, stackTraceElements.length);
        for (int i = 0; i < maxStackTraceElements; i++) {
            builder.addStackTrace(serializeStackTraceElement(stackTraceElements[i]));
        }

        if (t.getCause() != null) {
            builder.setCause(serializeThrowable(t.getCause()));
        }
        return builder.build();
    }

    private static StackTraceElementProto serializeStackTraceElement(StackTraceElement element) {
        final StackTraceElementProto.Builder builder =
                StackTraceElementProto.newBuilder()
                                      .setClassName(element.getClassName())
                                      .setMethodName(element.getMethodName())
                                      .setLineNumber(element.getLineNumber());
        if (element.getFileName() != null) {
            builder.setFileName(element.getFileName());
        }
        return builder.build();
    }

    private DefaultGrpcExceptionHandlerFunction() {}
}
