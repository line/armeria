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

import java.io.IOException;
import java.io.StringWriter;
import java.util.Base64;
import java.util.List;

import org.curioswitch.common.protobuf.json.MessageMarshaller;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.google.rpc.Status;

import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * An Utility class which contains methods related to Unframed grpc error handler.
 */
final class UnframedGrpcErrorHandlerUtils {

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

    static MessageMarshaller getErrorDetailsMarshaller() {
        return ERROR_DETAILS_MARSHALLER;
    }

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private UnframedGrpcErrorHandlerUtils() {}

    static JsonNode convertErrorDetailToJsonNode(List<Any> details, MessageMarshaller messageMarshaller)
            throws IOException {
        final StringWriter jsonObjectWriter = new StringWriter();
        try (JsonGenerator jsonGenerator = mapper.createGenerator(jsonObjectWriter)) {
            jsonGenerator.writeStartArray();
            for (Any detail : details) {
                messageMarshaller.writeValue(detail, jsonGenerator);
            }
            jsonGenerator.writeEndArray();
            jsonGenerator.flush();
            return mapper.readTree(jsonObjectWriter.toString());
        }
    }

    static Status decodeGrpcStatusDetailsBin(String grpcStatusDetailsBin)
            throws InvalidProtocolBufferException {
        final byte[] result = Base64.getDecoder().decode(grpcStatusDetailsBin);
        return Status.parseFrom(result);
    }
}
