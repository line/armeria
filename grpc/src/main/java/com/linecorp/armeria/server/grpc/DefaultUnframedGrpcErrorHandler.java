/*
 * Copyright 2022 LINE Corporation
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

import java.io.IOException;
import java.io.StringWriter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.BadRequest;
import com.google.rpc.DebugInfo;
import com.google.rpc.ErrorInfo;
import com.google.rpc.Help;
import com.google.rpc.LocalizedMessage;
import com.google.rpc.PreconditionFailure;
import com.google.rpc.QuotaFailure;
import com.google.rpc.RequestInfo;
import com.google.rpc.ResourceInfo;
import com.google.rpc.RetryInfo;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;
import io.grpc.Status.Code;

final class DefaultUnframedGrpcErrorHandler {

    private static final Logger logger = LoggerFactory.getLogger(DefaultUnframedGrpcErrorHandler.class);

    private static final MessageMarshaller ERROR_DETAILS_MARSHALLER =
            MessageMarshaller.builder()
                             .omittingInsignificantWhitespace(true)
                             .register(RetryInfo.getDefaultInstance())
                             .register(ErrorInfo.getDefaultInstance())
                             .register(QuotaFailure.getDefaultInstance())
                             .register(DebugInfo.getDefaultInstance())
                             .register(PreconditionFailure.getDefaultInstance())
                             .register(BadRequest.getDefaultInstance())
                             .register(RequestInfo.getDefaultInstance())
                             .register(ResourceInfo.getDefaultInstance())
                             .register(Help.getDefaultInstance())
                             .register(LocalizedMessage.getDefaultInstance())
                             .build();

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    /**
     * Ensure that unframedGrpcStatusMappingFunction never returns null by falling back to the default.
     */
    private static UnframedGrpcStatusMappingFunction ofStatusMappingFunction(
            UnframedGrpcStatusMappingFunction statusMappingFunction) {
        return requireNonNull(statusMappingFunction, "statusMappingFunction")
                .orElse(UnframedGrpcStatusMappingFunction.of());
    }

    static JsonNode convertErrorDetailToJsonNode(List<Any> details)
            throws IOException {
        final StringWriter jsonObjectWriter = new StringWriter();
        try (JsonGenerator jsonGenerator = mapper.createGenerator(jsonObjectWriter)) {
            jsonGenerator.writeStartArray();
            for (Any detail : details) {
                ERROR_DETAILS_MARSHALLER.writeValue(detail, jsonGenerator);
            }
            jsonGenerator.writeEndArray();
            jsonGenerator.flush();
            return mapper.readTree(jsonObjectWriter.toString());
        }
    }

    static com.google.rpc.Status decodeGrpcStatusDetailsBin(String grpcStatusDetailsBin)
            throws InvalidProtocolBufferException {
        final byte[] result = Base64.getDecoder().decode(grpcStatusDetailsBin);
        return com.google.rpc.Status.parseFrom(result);
    }

    @Nullable
    private static Throwable getThrowableFromContext(ServiceRequestContext ctx) {
        final RequestLogAccess log = ctx.log();
        final Throwable cause;
        if (log.isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
            cause = log.partial().responseCause();
        } else {
            cause = null;
        }
        return cause;
    }

    /**
     * Returns a plaintext or JSON response based on the content type.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        final UnframedGrpcStatusMappingFunction mappingFunction
                = ofStatusMappingFunction(statusMappingFunction);
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
     * Returns a JSON response.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler ofJson(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        final UnframedGrpcStatusMappingFunction mappingFunction
                = ofStatusMappingFunction(statusMappingFunction);
        return (ctx, status, response) -> {
            final Code grpcCode = status.getCode();
            @Nullable
            final String grpcMessage = status.getDescription();
            @Nullable
            final Throwable cause = getThrowableFromContext(ctx);
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
     * Returns a plaintext response.
     *
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler ofPlainText(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        final UnframedGrpcStatusMappingFunction mappingFunction
                = ofStatusMappingFunction(statusMappingFunction);
        return (ctx, status, response) -> {
            final Code grpcCode = status.getCode();
            @Nullable
            final String grpcMessage = status.getDescription();
            @Nullable
            final Throwable cause = getThrowableFromContext(ctx);
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
     * @param statusMappingFunction The function which maps the {@link Throwable} or gRPC {@link Status} code
     *                              to an {@link HttpStatus} code.
     */
    static UnframedGrpcErrorHandler ofRichJson(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        final UnframedGrpcStatusMappingFunction mappingFunction =
                requireNonNull(statusMappingFunction, "statusMappingFunction")
                        .orElse(UnframedGrpcStatusMappingFunction.of());
        return (ctx, status, response) -> {
            final Code grpcCode = status.getCode();
            @Nullable
            final String grpcMessage = status.getDescription();
            @Nullable
            final Throwable cause = getThrowableFromContext(ctx);
            final HttpStatus httpStatus = mappingFunction.apply(ctx, status, cause);
            final HttpHeaders trailers = !response.trailers().isEmpty() ?
                                         response.trailers() : response.headers();
            @Nullable
            final String grpcStatusDetailsBin = trailers.get(GrpcHeaderNames.GRPC_STATUS_DETAILS_BIN);
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
                    rpcStatus = decodeGrpcStatusDetailsBin(grpcStatusDetailsBin);
                } catch (InvalidProtocolBufferException e) {
                    logger.warn("invalid protobuf exception happens when decode grpc-status-details-bin {}",
                                grpcStatusDetailsBin);
                }
                if (rpcStatus != null) {
                    try {
                        messageBuilder.put("details", convertErrorDetailToJsonNode(rpcStatus.getDetailsList()));
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

    private DefaultUnframedGrpcErrorHandler() {}
}
