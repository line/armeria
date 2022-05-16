package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Map;

import javax.annotation.Nonnull;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;
import io.grpc.Status.Code;

class DefaultUnframedGrpcErrorHandler {

    static final Logger logger = LoggerFactory.getLogger(DefaultUnframedGrpcErrorHandler.class);

//    /**
//     * Returns a plain text or json response based on the content type.
//     */
//    static UnframedGrpcErrorHandler of() {
//        return of(UnframedGrpcStatusMappingFunction.of());
//    }

    private static UnframedGrpcStatusMappingFunction get(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        return requireNonNull(statusMappingFunction, "statusMappingFunction")
                .orElse(UnframedGrpcStatusMappingFunction.of());
    }

    private static ResponseHeadersBuilder buildResponseHeaders(ServiceRequestContext ctx, Status status, UnframedGrpcStatusMappingFunction mappingFunction) {
        final Code grpcCode = status.getCode();
        final RequestLogAccess log = ctx.log();
        final Throwable cause;
        if (log.isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
            cause = log.partial().responseCause();
        } else {
            cause = null;
        }
        final HttpStatus httpStatus = mappingFunction.apply(ctx, status, cause);
        return ResponseHeaders.builder(httpStatus)
                       .addInt(GrpcHeaderNames.GRPC_STATUS,
                               grpcCode.value());
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
        final UnframedGrpcStatusMappingFunction mappingFunction = get(statusMappingFunction);
        return (ctx, status, response) -> {
            final MediaType grpcMediaType = response.contentType();
            if (grpcMediaType != null && grpcMediaType.isJson()) {
                return ofJson(mappingFunction).handle(ctx, status, response);
            } else {
                return ofPlainText(mappingFunction).handle(ctx, status, response);
            }
        };
    }

//    /**
//     * Returns a json response.
//     */
//    static UnframedGrpcErrorHandler ofJson() {
//        return ofJson(UnframedGrpcStatusMappingFunction.of());
//    }


    /**
     * Returns a json response.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler ofJson(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        // Ensure that unframedGrpcStatusMappingFunction never returns null
        // by falling back to the default.
        final UnframedGrpcStatusMappingFunction mappingFunction = get(statusMappingFunction);
        return (ctx, status, response) -> {
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
                                                                           status.getCode().value())
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
        final UnframedGrpcStatusMappingFunction mappingFunction = get(statusMappingFunction);
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
     * Returns a rich error JSON response based on Google APIs.
     * Please refer <a href="https://cloud.google.com/apis/design/errors#error_model">Google error model</a>
     */
    static UnframedGrpcErrorHandler ofRichJson() {
        return ofRichJson(UnframedGrpcStatusMappingFunction.of());
    }

    /**
     * Returns a rich error JSON response based on Google APIs.
     * Please refer <a href="https://cloud.google.com/apis/design/errors#error_model">Google error model</a>
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler ofRichJson(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        // Ensure that unframedGrpcStatusMappingFunction never returns null
        // by falling back to the default.
        final UnframedGrpcStatusMappingFunction mappingFunction =
                requireNonNull(statusMappingFunction, "statusMappingFunction")
                        .orElse(UnframedGrpcStatusMappingFunction.of());
        final MessageMarshaller errorDetailsMarshaller
                = UnframedGrpcErrorHandlerUtils.getErrorDetailsMarshaller();
        return (ctx, status, response) -> {
            //final Logger logger = LoggerFactory.getLogger(UnframedGrpcErrorHandler.class);
            final Code grpcCode = status.getCode();
            final String grpcMessage = status.getDescription();
            final RequestLogAccess log = ctx.log();
            final Throwable cause;
            if (log.isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
                cause = log.partial().responseCause();
            } else {
                cause = null;
            }
            final HttpHeaders trailers = !response.trailers().isEmpty() ?
                                         response.trailers() : response.headers();
            final String grpcStatusDetailsBin = trailers.get(GrpcHeaderNames.GRPC_STATUS_DETAILS_BIN);
            final HttpStatus httpStatus = mappingFunction.apply(ctx, status, cause);
            final ResponseHeaders responseHeaders = ResponseHeaders.builder(httpStatus)
                                                                   .contentType(MediaType.JSON_UTF_8)
                                                                   .addInt(GrpcHeaderNames.GRPC_STATUS,
                                                                           grpcCode.value())
                                                                   .build();
            final ImmutableMap.Builder<String, Object> messageBuilder = ImmutableMap.builder();
            messageBuilder.put("code", httpStatus.code());
            messageBuilder.put("status", grpcCode.name());
            if (grpcMessage != null) {
                messageBuilder.put("message", grpcMessage);
            }
            if (cause != null && ctx.config().verboseResponses()) {
                messageBuilder.put("stack-trace", Exceptions.traceText(cause));
            }
            if (!Strings.isNullOrEmpty(grpcStatusDetailsBin)) {
                com.google.rpc.Status rpcStatus = null;
                try {
                    rpcStatus = UnframedGrpcErrorHandlerUtils.decodeGrpcStatusDetailsBin(grpcStatusDetailsBin);
                } catch (InvalidProtocolBufferException e) {
                    logger.warn("invalid protobuf exception happens when decode grpc-status-details-bin {}",
                                grpcStatusDetailsBin);
                }
                if (rpcStatus != null) {
                    try {
                        messageBuilder.put("details", UnframedGrpcErrorHandlerUtils
                                .convertErrorDetailToJsonNode(rpcStatus.getDetailsList(),
                                                              errorDetailsMarshaller));
                    } catch (IOException e) {
                        logger.warn("error happens when convert error converting rpc status {} to strings",
                                    rpcStatus);
                    }
                }
            }
            final Map<String, Object> errorObject = ImmutableMap.of("error", messageBuilder.build());
            return HttpResponse.ofJson(responseHeaders, errorObject);
        };
    }

}
