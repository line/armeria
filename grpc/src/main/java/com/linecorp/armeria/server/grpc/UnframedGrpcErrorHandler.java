/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;
import io.grpc.Status.Code;

/**
 * Error handler which maps a gRPC response to an {@link HttpResponse}.
 */
@FunctionalInterface
@UnstableApi
public interface UnframedGrpcErrorHandler {

    /**
     * Returns a plain text or json response based on the content type.
     */
    static UnframedGrpcErrorHandler of() {
        return of(UnframedGrpcStatusMappingFunction.of());
    }

    /**
     * Returns a plain text or json response based on the content type.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        // Ensure that unframedGrpcStatusMappingFunction never returns null
        // by falling back to the default.
        final UnframedGrpcStatusMappingFunction mappingFunction =
                requireNonNull(statusMappingFunction, "statusMappingFunction")
                        .orElse(UnframedGrpcStatusMappingFunction.of());
        return (ctx, status, response) -> {
            final MediaType grpcMediaType = response.contentType();
            if (grpcMediaType != null && grpcMediaType.isJson()) {
                return ofJson(mappingFunction).handle(ctx, status, response);
            } else {
                return ofPlainText(mappingFunction).handle(ctx, status, response);
            }
        };
    }

    /**
     * Returns a json response.
     */
    static UnframedGrpcErrorHandler ofJson() {
        return ofJson(UnframedGrpcStatusMappingFunction.of());
    }

    /**
     * Returns a json response.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler ofJson(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        // Ensure that unframedGrpcStatusMappingFunction never returns null
        // by falling back to the default.
        final UnframedGrpcStatusMappingFunction mappingFunction =
                requireNonNull(statusMappingFunction, "statusMappingFunction")
                        .orElse(UnframedGrpcStatusMappingFunction.of());
        return (ctx, status, response) -> {
            final Code grpcCode = status.getCode();
            final String grpcMessage = status.getDescription();
            final RequestLogAccess log = ctx.log();
            final Throwable cause;
            if (log.isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
                cause = log.partial().responseCause();
            } else {
                cause = null;
            }
            final HttpStatus httpStatus = mappingFunction.apply(ctx, status, cause);
            final ResponseHeaders responseHeaders = ResponseHeaders.builder(httpStatus)
                                                                   .contentType(MediaType.JSON_UTF_8)
                                                                   .addInt(GrpcHeaderNames.GRPC_STATUS,
                                                                           grpcCode.value())
                                                                   .build();
            final ImmutableMap.Builder<String, String> messageBuilder = ImmutableMap.builder();
            messageBuilder.put("grpc-code", grpcCode.name());
            if (grpcMessage != null) {
                messageBuilder.put("message", grpcMessage);
            }
            if (cause != null && ctx.config().verboseResponses()) {
                messageBuilder.put("stack-trace", Exceptions.traceText(cause));
            }
            return HttpResponse.ofJson(responseHeaders, messageBuilder.build());
        };
    }

    /**
     * Returns a plain text response.
     */
    static UnframedGrpcErrorHandler ofPlainText() {
        return ofPlainText(UnframedGrpcStatusMappingFunction.of());
    }

    /**
     * Returns a plain text response.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler ofPlainText(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        // Ensure that unframedGrpcStatusMappingFunction never returns null
        // by falling back to the default.
        final UnframedGrpcStatusMappingFunction mappingFunction =
                requireNonNull(statusMappingFunction, "statusMappingFunction")
                        .orElse(UnframedGrpcStatusMappingFunction.of());
        return (ctx, status, response) -> {
            final Code grpcCode = status.getCode();
            final RequestLogAccess log = ctx.log();
            final Throwable cause;
            if (log.isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
                cause = log.partial().responseCause();
            } else {
                cause = null;
            }
            final HttpStatus httpStatus = mappingFunction.apply(ctx, status, cause);
            final ResponseHeaders responseHeaders = ResponseHeaders.builder(httpStatus)
                                                                   .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                                                   .addInt(GrpcHeaderNames.GRPC_STATUS,
                                                                           grpcCode.value())
                                                                   .build();
            final HttpData content;
            try (TemporaryThreadLocals ttl = TemporaryThreadLocals.acquire()) {
                final StringBuilder msg = ttl.stringBuilder();
                msg.append("grpc-code: ").append(grpcCode.name());
                final String grpcMessage = status.getDescription();
                if (grpcMessage != null) {
                    msg.append(", ").append(grpcMessage);
                }
                if (cause != null && ctx.config().verboseResponses()) {
                    msg.append("\nstack-trace:\n").append(Exceptions.traceText(cause));
                }
                content = HttpData.ofUtf8(msg);
            }
            return HttpResponse.of(responseHeaders, content);
        };
    }

    /**
     * Maps the gRPC error response to the {@link HttpResponse}.
     *
     * @param ctx the service context.
     * @param status the gRPC {@link Status} code.
     * @param response the gRPC response.
     *
     * @return the {@link HttpResponse}.
     */
    HttpResponse handle(ServiceRequestContext ctx, Status status, AggregatedHttpResponse response);
}
