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

package com.linecorp.armeria.server.grpc;

import static com.linecorp.armeria.server.grpc.UnframedGrpcErrorHandlerUtil.responseCause;
import static com.linecorp.armeria.server.grpc.UnframedGrpcErrorHandlerUtil.withDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;
import java.util.List;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;
import io.grpc.Status.Code;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;

/**
 * Error handler which maps a gRPC response to an {@link HttpResponse} in json format.
 */
final class JsonUnframedGrpcErrorHandler implements UnframedGrpcErrorHandler {

    private static final Logger logger = LoggerFactory.getLogger(JsonUnframedGrpcErrorHandler.class);

    static final MessageMarshaller ERROR_DETAILS_MARSHALLER =
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

    private static final UnframedGrpcStatusMappingFunction DEFAULT_STATUS_MAPPING_FUNCTION =
            UnframedGrpcStatusMappingFunction.of();

    private static final JsonUnframedGrpcErrorHandler DEFAULT =
            new JsonUnframedGrpcErrorHandler(DEFAULT_STATUS_MAPPING_FUNCTION, ERROR_DETAILS_MARSHALLER);

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    static JsonUnframedGrpcErrorHandler of() {
        return DEFAULT;
    }

    static JsonUnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction statusMappingFunction) {
        return of(statusMappingFunction, ERROR_DETAILS_MARSHALLER);
    }

    static JsonUnframedGrpcErrorHandler of(UnframedGrpcStatusMappingFunction statusMappingFunction,
                                           MessageMarshaller jsonMarshaller) {
        if (DEFAULT_STATUS_MAPPING_FUNCTION == statusMappingFunction &&
            ERROR_DETAILS_MARSHALLER == jsonMarshaller) {
            return DEFAULT;
        }
        return new JsonUnframedGrpcErrorHandler(statusMappingFunction, jsonMarshaller);
    }

    private final UnframedGrpcStatusMappingFunction statusMappingFunction;
    private final MessageMarshaller jsonMarshaller;

    private JsonUnframedGrpcErrorHandler(UnframedGrpcStatusMappingFunction statusMappingFunction,
                                         MessageMarshaller jsonMarshaller) {
        this.statusMappingFunction = withDefault(statusMappingFunction);
        this.jsonMarshaller = jsonMarshaller;
    }

    /**
     * Returns a JSON response based on Google APIs.
     * Please refer to <a href="https://cloud.google.com/apis/design/errors#error_model">Google error model</a>
     *
     * @param ctx the service context.
     * @param status the gRPC {@link Status} code.
     * @param response the gRPC response.
     *
     * @return the {@link HttpResponse}.
     */
    @Override
    public HttpResponse handle(ServiceRequestContext ctx, Status status, AggregatedHttpResponse response) {
        final ByteBuf buffer = ctx.alloc().buffer();
        final Code grpcCode = status.getCode();
        final String grpcMessage = status.getDescription();
        final Throwable cause = responseCause(ctx);
        final HttpStatus httpStatus = statusMappingFunction.apply(ctx, status, cause);
        final HttpHeaders trailers = !response.trailers().isEmpty() ?
                                     response.trailers() : response.headers();
        final String grpcStatusDetailsBin = trailers.get(GrpcHeaderNames.GRPC_STATUS_DETAILS_BIN);
        final ResponseHeaders responseHeaders = ResponseHeaders.builder(httpStatus)
                                                               .contentType(MediaType.JSON_UTF_8)
                                                               .addInt(GrpcHeaderNames.GRPC_STATUS,
                                                                       grpcCode.value())
                                                               .build();
        boolean success = false;
        try (OutputStream outputStream = new ByteBufOutputStream(buffer);
             JsonGenerator jsonGenerator = mapper.createGenerator(outputStream)) {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeNumberField("code", grpcCode.value());
            jsonGenerator.writeStringField("grpc-code", grpcCode.name());
            if (grpcMessage != null) {
                jsonGenerator.writeStringField("message", grpcMessage);
            }
            if (cause != null && ctx.config().verboseResponses()) {
                jsonGenerator.writeStringField("stack-trace", Exceptions.traceText(cause));
            }
            if (!Strings.isNullOrEmpty(grpcStatusDetailsBin)) {
                com.google.rpc.Status rpcStatus = null;
                try {
                    rpcStatus = decodeGrpcStatusDetailsBin(grpcStatusDetailsBin);
                } catch (InvalidProtocolBufferException e) {
                    logger.warn("Unexpected exception while decoding grpc-status-details-bin: {}",
                                grpcStatusDetailsBin, e);
                }
                if (rpcStatus != null) {
                    jsonGenerator.writeFieldName("details");
                    writeErrorDetails(ctx, rpcStatus.getDetailsList(), jsonGenerator);
                }
            }
            jsonGenerator.writeEndObject();
            jsonGenerator.flush();
            success = true;
        } catch (IOException e) {
            logger.warn("{} Unexpected exception while generating a JSON response", ctx, e);
        } finally {
            if (!success) {
                buffer.release();
            }
        }
        if (success) {
            return HttpResponse.of(responseHeaders, HttpData.wrap(buffer));
        } else {
            return HttpResponse.of(responseHeaders);
        }
    }

    @VisibleForTesting
    void writeErrorDetails(
            ServiceRequestContext ctx, List<Any> details, JsonGenerator jsonGenerator) throws IOException {
        jsonGenerator.writeStartArray();
        for (Any detail : details) {
            try {
                jsonMarshaller.writeValue(detail, jsonGenerator);
            } catch (IOException e) {
                logger.warn("Unexpected exception while writing an error detail to JSON." +
                            " ctx: '{}', detail: '{}'", ctx, detail, e);
            }
        }
        jsonGenerator.writeEndArray();
    }

    @VisibleForTesting
    static com.google.rpc.Status decodeGrpcStatusDetailsBin(String grpcStatusDetailsBin)
            throws InvalidProtocolBufferException {
        final byte[] result = Base64.getDecoder().decode(grpcStatusDetailsBin);
        return com.google.rpc.Status.parseFrom(result);
    }
}
