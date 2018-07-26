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

package com.linecorp.armeria.internal.grpc;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.channels.ClosedChannelException;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.HttpStatus;

import io.grpc.Status;
import io.grpc.Status.Code;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Exception.StreamException;

/**
 * Utilities for handling {@link Status} in Armeria.
 */
public final class GrpcStatus {

    /**
     * Converts the {@link Throwable} to a {@link Status}, taking into account exceptions specific to Armeria as
     * well.
     */
    public static Status fromThrowable(Throwable t) {
        requireNonNull(t, "t");
        final Status s = Status.fromThrowable(t);
        if (s.getCode() != Code.UNKNOWN) {
            return s;
        }
        if (t instanceof StreamException) {
            final StreamException streamException = (StreamException) t;
            if (streamException.getMessage() != null && streamException.getMessage().contains("RST_STREAM")) {
                return Status.CANCELLED;
            }
        }
        if (t instanceof ClosedChannelException) {
            // ClosedChannelException is used any time the Netty channel is closed. Proper error
            // processing requires remembering the error that occurred before this one and using it
            // instead.
            return Status.UNKNOWN.withCause(t);
        }
        if (t instanceof IOException) {
            return Status.UNAVAILABLE.withCause(t);
        }
        if (t instanceof Http2Exception) {
            return Status.INTERNAL.withCause(t);
        }
        if (t instanceof ResponseTimeoutException) {
            return Status.DEADLINE_EXCEEDED.withCause(t);
        }
        return s;
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

    private GrpcStatus() {}
}
