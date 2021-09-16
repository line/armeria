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

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
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
        return (ctx, status, response) -> {
            final MediaType grpcMediaType = response.contentType();
            if (grpcMediaType != null && grpcMediaType.isJson()) {
                return ofJson().handle(ctx, status, response);
            } else {
                return ofPlainText().handle(ctx, status, response);
            }
        };
    }

    /**
     * Returns a json response.
     */
    static UnframedGrpcErrorHandler ofJson() {
        return (ctx, status, response) -> {
            final HttpHeaders headers = !response.trailers().isEmpty() ?
                                        response.trailers() : response.headers();
            final Code grpcCode = status.getCode();
            final HttpStatus httpStatus = GrpcStatus.grpcCodeToHttpStatus(grpcCode);
            final ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
            builder.put("code", grpcCode.name());
            final String grpcMessage = headers.get(GrpcHeaderNames.GRPC_MESSAGE);
            if (grpcMessage != null) {
                builder.put("message", grpcMessage);
            }
            final ResponseHeaders responseHeaders = ResponseHeaders.builder(httpStatus)
                                                                   .contentType(MediaType.JSON_UTF_8)
                                                                   .addInt(GrpcHeaderNames.GRPC_STATUS,
                                                                           grpcCode.value())
                                                                   .build();
            return HttpResponse.ofJson(responseHeaders, builder.build());
        };
    }

    /**
     * Returns a plain text response.
     */
    static UnframedGrpcErrorHandler ofPlainText() {
        return (ctx, status, response) -> {
            final HttpHeaders headers = !response.trailers().isEmpty() ?
                                        response.trailers() : response.headers();
            final Code grpcCode = status.getCode();
            final HttpStatus httpStatus = GrpcStatus.grpcCodeToHttpStatus(grpcCode);
            final StringBuilder message = new StringBuilder("grpc-code: " + grpcCode.name());
            final String grpcMessage = headers.get(GrpcHeaderNames.GRPC_MESSAGE);
            if (grpcMessage != null) {
                message.append(", ").append(grpcMessage);
            }
            final ResponseHeaders responseHeaders = ResponseHeaders.builder(httpStatus)
                                                                   .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                                                   .addInt(GrpcHeaderNames.GRPC_STATUS,
                                                                           grpcCode.value())
                                                                   .build();
            return HttpResponse.of(responseHeaders, HttpData.ofUtf8(message.toString()));
        };
    }

    /**
     * Maps the gRPC error response to the {@link HttpResponse}.
     *
     * @param ctx the service context.
     * @param status the gRPC {@link Status} code.
     * @param response the gRPC response.
     *
     * @return the http response.
     */
    HttpResponse handle(ServiceRequestContext ctx, Status status, AggregatedHttpResponse response);
}
