/*
 * Copyright 2017 LINE Corporation
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
/*
 * Copyright 2014, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.internal.common.grpc;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.channels.ClosedChannelException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.grpc.StackTraceElementProto;
import com.linecorp.armeria.common.grpc.StatusCauseException;
import com.linecorp.armeria.common.grpc.ThrowableProto;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.StatusMessageEscaper;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.stream.HttpDeframer;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;

/**
 * Utilities for handling {@link Status} in Armeria.
 */
public final class GrpcStatus {

    private static final Logger logger = LoggerFactory.getLogger(GrpcStatus.class);

    /**
     * Converts the {@link Throwable} to a {@link Status}, taking into account exceptions specific to Armeria as
     * well and the protocol package.
     */
    public static Status fromThrowable(
            @Nullable List<Map.Entry<Class<? extends Throwable>, Status>> exceptionMappings, Throwable t) {
        t = unwrap(requireNonNull(t, "t"));

        if (exceptionMappings != null) {
            for (Map.Entry<Class<? extends Throwable>, Status> exceptionMapping : exceptionMappings) {
                if (exceptionMapping.getKey().isInstance(t)) {
                    return exceptionMapping.getValue().withCause(t);
                }
            }
        }

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
        if (t instanceof ClosedStreamException) {
            return Status.CANCELLED;
        }
        if (t instanceof UnprocessedRequestException || t instanceof IOException) {
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
     * using the specified exception mapping rules.
     * The {@link Status#getCause()} of the specified {@link Status} is copied to a new derived {@link Status}.
     * Returns the given {@link Status} as is if fails to find a mapping rule.
     */
    public static Status fromMappingRule(
            @Nullable List<Map.Entry<Class<? extends Throwable>, Status>> exceptionMappings, Status status) {
        requireNonNull(status, "status");

        final Throwable cause = status.getCause();
        if (exceptionMappings != null && cause != null) {
            final Throwable unwrapped = unwrap(cause);
            for (Map.Entry<Class<? extends Throwable>, Status> exceptionMapping : exceptionMappings) {
                if (exceptionMapping.getKey().isInstance(unwrapped)) {
                    return exceptionMapping.getValue().withCause(cause);
                }
            }
        }
        return status;
    }

    public static void addExceptionMapping(
            LinkedList<Map.Entry<Class<? extends Throwable>, Status>> exceptionMappings,
            Class<? extends Throwable> exceptionType, Status status) {
        requireNonNull(exceptionMappings, "exceptionMappings");
        requireNonNull(exceptionType, "exceptionType");
        requireNonNull(status, "status");

        final ListIterator<Map.Entry<Class<? extends Throwable>, Status>> it =
                exceptionMappings.listIterator();

        while (it.hasNext()) {
            final Map.Entry<Class<? extends Throwable>, Status> next = it.next();
            final Class<? extends Throwable> oldExceptionType = next.getKey();
            checkArgument(oldExceptionType != exceptionType, "%s is already added with %s",
                          oldExceptionType, next.getValue());

            if (oldExceptionType.isAssignableFrom(exceptionType)) {
                // exceptionType is a subtype of oldExceptionType. exceptionType needs a higher priority.
                it.previous();
                it.add(new SimpleImmutableEntry<>(exceptionType, status));
                return;
            }
        }

        exceptionMappings.add(new SimpleImmutableEntry<>(exceptionType, status));
    }

    private static Throwable unwrap(Throwable t) {
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
     * Maps GRPC status codes to http status, as defined in upstream grpc-gateway
     * <a href="https://github.com/grpc-ecosystem/grpc-gateway/blob/master/third_party/googleapis/google/rpc/code.proto">code.proto</a>.
     */
    public static HttpStatus grpcCodeToHttpStatus(Status.Code grpcStatusCode) {
        switch (grpcStatusCode) {
            case OK:
                return HttpStatus.OK;
            case CANCELLED:
                return HttpStatus.CLIENT_CLOSED_REQUEST;
            case UNKNOWN:
            case INTERNAL:
            case DATA_LOSS:
                return HttpStatus.INTERNAL_SERVER_ERROR;
            case INVALID_ARGUMENT:
            case FAILED_PRECONDITION:
            case OUT_OF_RANGE:
                return HttpStatus.BAD_REQUEST;
            case DEADLINE_EXCEEDED:
                return HttpStatus.GATEWAY_TIMEOUT;
            case NOT_FOUND:
                return HttpStatus.NOT_FOUND;
            case ALREADY_EXISTS:
            case ABORTED:
                return HttpStatus.CONFLICT;
            case PERMISSION_DENIED:
                return HttpStatus.FORBIDDEN;
            case UNAUTHENTICATED:
                return HttpStatus.UNAUTHORIZED;
            case RESOURCE_EXHAUSTED:
                return HttpStatus.TOO_MANY_REQUESTS;
            case UNIMPLEMENTED:
                return HttpStatus.NOT_IMPLEMENTED;
            case UNAVAILABLE:
                return HttpStatus.SERVICE_UNAVAILABLE;
            default:
                return HttpStatus.UNKNOWN;
        }
    }

    /**
     * Maps HTTP error response status codes to transport codes, as defined in <a
     * href="https://github.com/grpc/grpc/blob/master/doc/http-grpc-status-mapping.md">
     * http-grpc-status-mapping.md</a>. Never returns a status for which {@code status.isOk()} is
     * {@code true}.
     *
     * <p>Copied from <a href="https://github.com/grpc/grpc-java/blob/master/core/src/main/java/io/grpc/internal/GrpcUtil.java">
     * GrpcUtil.java</a>
     */
    public static Status httpStatusToGrpcStatus(int httpStatusCode) {
        return httpStatusToGrpcCode(httpStatusCode).toStatus()
                                                   .withDescription("HTTP status code " + httpStatusCode);
    }

    private static Status.Code httpStatusToGrpcCode(int httpStatusCode) {
        if (httpStatusCode >= 100 && httpStatusCode < 200) {
            // 1xx. These headers should have been ignored.
            return Status.Code.INTERNAL;
        }
        switch (httpStatusCode) {
            case HttpURLConnection.HTTP_BAD_REQUEST:  // 400
            case 431: // Request Header Fields Too Large
                return Status.Code.INTERNAL;
            case HttpURLConnection.HTTP_UNAUTHORIZED:  // 401
                return Status.Code.UNAUTHENTICATED;
            case HttpURLConnection.HTTP_FORBIDDEN:  // 403
                return Status.Code.PERMISSION_DENIED;
            case HttpURLConnection.HTTP_NOT_FOUND:  // 404
                return Status.Code.UNIMPLEMENTED;
            case 429:  // Too Many Requests
            case HttpURLConnection.HTTP_BAD_GATEWAY:  // 502
            case HttpURLConnection.HTTP_UNAVAILABLE:  // 503
            case HttpURLConnection.HTTP_GATEWAY_TIMEOUT:  // 504
                return Status.Code.UNAVAILABLE;
            default:
                return Status.Code.UNKNOWN;
        }
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

    /**
     * Extracts the gRPC status from the {@link HttpHeaders}, closing the {@link HttpStreamDeframerHandler}
     * for a successful response, then delivering the status to the {@link TransportStatusListener}.
     */
    public static void reportStatus(HttpHeaders headers,
                                    HttpDeframer<DeframedMessage> reader,
                                    TransportStatusListener transportStatusListener) {
        final String grpcStatus = headers.get(GrpcHeaderNames.GRPC_STATUS);
        Status status = Status.fromCodeValue(Integer.valueOf(grpcStatus));
        if (status.getCode() == Status.OK.getCode()) {
            // Successful response, finish delivering messages before returning the status.
            reader.close();
        }
        final String grpcMessage = headers.get(GrpcHeaderNames.GRPC_MESSAGE);
        if (grpcMessage != null) {
            status = status.withDescription(StatusMessageEscaper.unescape(grpcMessage));
        }
        final String grpcThrowable = headers.get(GrpcHeaderNames.ARMERIA_GRPC_THROWABLEPROTO_BIN);
        if (grpcThrowable != null) {
            status = addCause(status, grpcThrowable);
        }

        final Metadata metadata = MetadataUtil.copyFromHeaders(headers);

        transportStatusListener.transportReportStatus(status, metadata);
    }

    private static Status addCause(Status status, String serializedThrowableProto) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(serializedThrowableProto);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid Base64 in status cause proto, ignoring.", e);
            return status;
        }
        final ThrowableProto grpcThrowableProto;
        try {
            grpcThrowableProto = ThrowableProto.parseFrom(decoded);
        } catch (InvalidProtocolBufferException e) {
            logger.warn("Invalid serialized status cause proto, ignoring.", e);
            return status;
        }
        return status.withCause(new StatusCauseException(grpcThrowableProto));
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

    private GrpcStatus() {}
}
