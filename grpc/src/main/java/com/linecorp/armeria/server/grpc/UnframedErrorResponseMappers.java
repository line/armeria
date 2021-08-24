/*
 * Copyright 2020 LINE Corporation
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

import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;

import io.grpc.Status;

final class UnframedErrorResponseMappers {

    static final Function<HttpHeaders, HttpResponse> DEFAULT_UNFRAMED_RESPONSE_MAPPER = headers -> {
        final String grpcStatusCode = headers.get(GrpcHeaderNames.GRPC_STATUS);
        final Status grpcStatus = Status.fromCodeValue(Integer.parseInt(grpcStatusCode));
        final HttpStatus httpStatus = GrpcStatus.grpcCodeToHttpStatus(grpcStatus.getCode());
        final StringBuilder message = new StringBuilder("http-status: " + httpStatus.code());
        message.append(", ").append(httpStatus.reasonPhrase()).append('\n');
        message.append("Caused by: ").append('\n');
        message.append("grpc-status: ")
               .append(grpcStatusCode)
               .append(", ")
               .append(grpcStatus.getCode().name());
        final String grpcMessage = headers.get(GrpcHeaderNames.GRPC_MESSAGE);
        if (grpcMessage != null) {
            message.append(", ").append(grpcMessage);
        }
        final ResponseHeaders responseHeaders = ResponseHeaders.builder(httpStatus)
                                                               .contentType(MediaType.PLAIN_TEXT_UTF_8)
                                                               .add(GrpcHeaderNames.GRPC_STATUS, grpcStatusCode)
                                                               .build();
        return HttpResponse.of(responseHeaders, HttpData.ofUtf8(message.toString()));
    };

    static final Function<HttpHeaders, HttpResponse> JSON_UNFRAMED_RESPONSE_MAPPER = headers -> {
        final String grpcStatusCode = headers.get(GrpcHeaderNames.GRPC_STATUS);
        final Status grpcStatus = Status.fromCodeValue(Integer.parseInt(grpcStatusCode));
        final HttpStatus httpStatus = GrpcStatus.grpcCodeToHttpStatus(grpcStatus.getCode());
        final ImmutableMap.Builder<String, Object> builder =
                ImmutableMap.<String, Object>builder()
                            .put("code", grpcStatus.getCode().name())
                            .put("http_status", httpStatus.code())
                            .put("grpc_status", grpcStatusCode);
        final String grpcMessage = headers.get(GrpcHeaderNames.GRPC_MESSAGE);
        if (grpcMessage != null) {
            builder.put("message", grpcMessage);
        }
        final ResponseHeaders responseHeaders = ResponseHeaders.builder(httpStatus)
                                                               .contentType(MediaType.JSON_UTF_8)
                                                               .add(GrpcHeaderNames.GRPC_STATUS, grpcStatusCode)
                                                               .build();
        return HttpResponse.ofJson(responseHeaders, builder.build());
    };

    private UnframedErrorResponseMappers() {}
}
