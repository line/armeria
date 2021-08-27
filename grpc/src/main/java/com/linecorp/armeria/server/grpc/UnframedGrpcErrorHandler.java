package com.linecorp.armeria.server.grpc;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;

@FunctionalInterface
public interface UnframedGrpcErrorHandler {

    static UnframedGrpcErrorHandler of() {
        return (ctx, status, response) -> {
            final HttpHeaders headers = !response.trailers().isEmpty() ?
                                        response.trailers() : response.headers();
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
                                                                   .add(GrpcHeaderNames.GRPC_STATUS,
                                                                        grpcStatusCode)
                                                                   .build();
            return HttpResponse.of(responseHeaders, HttpData.ofUtf8(message.toString()));
        };
    }

    static UnframedGrpcErrorHandler ofJson() {
        return (ctx, status, response) -> {
            final HttpHeaders headers = !response.trailers().isEmpty() ?
                                        response.trailers() : response.headers();
            final String grpcStatusCode = headers.get(GrpcHeaderNames.GRPC_STATUS);
            final Status grpcStatus = Status.fromCodeValue(Integer.parseInt(grpcStatusCode));
            final HttpStatus httpStatus = GrpcStatus.grpcCodeToHttpStatus(grpcStatus.getCode());
            final ImmutableMap.Builder<String, Object> builder =
                    ImmutableMap.<String, Object>builder().put("code", grpcStatus.getCode().name());
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
    }

    HttpResponse handler(ServiceRequestContext ctx, Status status, AggregatedHttpResponse response);
}
