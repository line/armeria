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

import java.net.HttpURLConnection;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.StackTraceElementProto;
import com.linecorp.armeria.common.grpc.StatusCauseException;
import com.linecorp.armeria.common.grpc.ThrowableProto;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.StatusMessageEscaper;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;

/**
 * Utilities for handling {@link Status} in Armeria.
 */
public final class GrpcStatus {

    private static final Logger logger = LoggerFactory.getLogger(GrpcStatus.class);

    /**
     * Maps gRPC {@link Status} to {@link HttpStatus}. If there is no matched rule for the specified
     * {@link Status}, the mapping rules defined in upstream Google APIs
     * <a href="https://github.com/googleapis/googleapis/blob/b2a7d2709887e38bcd3b5142424e563b0b386b6f/google/rpc/code.proto">
     * code.proto</a> will be used to convert the {@linkplain Status#getCode() gRPC code} to
     * the {@link HttpStatus}.
     */
    public static HttpStatus grpcStatusToHttpStatus(ServiceRequestContext ctx, Status grpcStatus) {
        if (grpcStatus.getCode() == Code.CANCELLED) {
            final RequestLog log = ctx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
            if (log != null) {
                final Throwable responseCause = log.responseCause();
                if (responseCause != null && (responseCause instanceof RequestTimeoutException ||
                                              (responseCause.getCause() instanceof RequestTimeoutException))) {
                    // A call was closed by a server-side timeout.
                    return HttpStatus.SERVICE_UNAVAILABLE;
                }
            }
            // TODO(minwoox): Do not rely on the message to determine the cause of cancellation.
            if ("Completed without a response".equals(grpcStatus.getDescription())) {
                // A unary call was closed without sending a response.
                return HttpStatus.INTERNAL_SERVER_ERROR;
            }
        }
        return grpcCodeToHttpStatus(grpcStatus.getCode());
    }

    /**
     * Maps gRPC status codes to HTTP status, as defined in upstream Google APIs
     * <a href="https://github.com/googleapis/googleapis/blob/b2a7d2709887e38bcd3b5142424e563b0b386b6f/google/rpc/code.proto">
     * code.proto</a>.
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
     * <p>Copied from
     * <a href="https://github.com/grpc/grpc-java/blob/master/core/src/main/java/io/grpc/internal/GrpcUtil.java">
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
     * Extracts the gRPC status from the {@link HttpHeaders} and delivers the status
     * to the {@link TransportStatusListener} when the response is completed.
     */
    public static void reportStatusLater(HttpHeaders headers,
                                         StreamMessage<DeframedMessage> deframedStreamMessage,
                                         TransportStatusListener transportStatusListener) {
        deframedStreamMessage.whenComplete().handle((unused1, unused2) -> {
            reportStatus(headers, transportStatusListener);
            return null;
        });
    }

    /**
     * Extracts the gRPC status from the {@link HttpHeaders} and delivers the status
     * to the {@link TransportStatusListener} immediately.
     */
    public static void reportStatus(HttpHeaders headers, TransportStatusListener transportStatusListener) {
        final String grpcStatus = headers.get(GrpcHeaderNames.GRPC_STATUS);
        Status status = Status.fromCodeValue(Integer.valueOf(grpcStatus));
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
