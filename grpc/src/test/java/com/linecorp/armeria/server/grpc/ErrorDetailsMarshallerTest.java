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

import static com.linecorp.armeria.server.grpc.JsonUnframedGrpcErrorHandler.ERROR_DETAILS_MARSHALLER;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringWriter;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.rpc.BadRequest;
import com.google.rpc.BadRequest.FieldViolation;
import com.google.rpc.Code;
import com.google.rpc.DebugInfo;
import com.google.rpc.ErrorInfo;
import com.google.rpc.Help;
import com.google.rpc.Help.Link;
import com.google.rpc.LocalizedMessage;
import com.google.rpc.PreconditionFailure;
import com.google.rpc.QuotaFailure;
import com.google.rpc.QuotaFailure.Violation;
import com.google.rpc.RequestInfo;
import com.google.rpc.ResourceInfo;
import com.google.rpc.RetryInfo;
import com.google.rpc.Status;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.grpc.testing.Error.AuthError;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

class ErrorDetailsMarshallerTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    @Test
    void convertErrorDetailToJsonNodeTest() throws IOException {
        final ErrorInfo errorInfo = ErrorInfo.newBuilder()
                                             .setDomain("test")
                                             .setReason("Unknown Exception")
                                             .putMetadata("key", "value")
                                             .build();
        final RetryInfo retryInfo = RetryInfo.newBuilder()
                                             .setRetryDelay(Duration.newBuilder()
                                                                    .setSeconds(100)
                                                                    .build())
                                             .build();
        final DebugInfo debugInfo = DebugInfo.newBuilder()
                                             .setDetail("debug info")
                                             .addStackEntries("stack1")
                                             .addStackEntries("stack2")
                                             .build();
        final QuotaFailure quotaFailure =
                QuotaFailure.newBuilder()
                            .addViolations(Violation.newBuilder().setDescription("quota").build())
                            .build();

        final PreconditionFailure preconditionFailure =
                PreconditionFailure.newBuilder()
                                   .addViolations(
                                           PreconditionFailure.Violation.newBuilder()
                                                                        .setDescription("violation")
                                                                        .build())
                                   .build();
        final BadRequest badRequest =
                BadRequest.newBuilder()
                          .addFieldViolations(
                                  FieldViolation.newBuilder()
                                                .setDescription("field violation")
                                                .build())
                          .build();
        final RequestInfo requestInfo = RequestInfo.newBuilder()
                                                   .setRequestId("requestid")
                                                   .build();
        final ResourceInfo resourceInfo = ResourceInfo.newBuilder()
                                                      .setDescription("description")
                                                      .build();
        final Help help = Help.newBuilder()
                              .addLinks(Link.newBuilder()
                                            .setDescription("descrption")
                                            .build())
                              .build();
        final LocalizedMessage localizedMessage = LocalizedMessage.newBuilder()
                                                                  .setMessage("message")
                                                                  .build();

        final Status status = Status.newBuilder()
                                    .setCode(Code.UNKNOWN.getNumber())
                                    .setMessage("Unknown Exceptions Test")
                                    .addDetails(Any.pack(errorInfo))
                                    .addDetails(Any.pack(retryInfo))
                                    .addDetails(Any.pack(debugInfo))
                                    .addDetails(Any.pack(quotaFailure))
                                    .addDetails(Any.pack(preconditionFailure))
                                    .addDetails(Any.pack(badRequest))
                                    .addDetails(Any.pack(requestInfo))
                                    .addDetails(Any.pack(resourceInfo))
                                    .addDetails(Any.pack(help))
                                    .addDetails(Any.pack(localizedMessage))
                                    .build();

        final StringWriter jsonObjectWriter = new StringWriter();
        final JsonGenerator jsonGenerator = mapper.createGenerator(jsonObjectWriter);
        final JsonUnframedGrpcErrorHandler jsonUnframedGrpcErrorHandler = JsonUnframedGrpcErrorHandler.of();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/test"));
        jsonUnframedGrpcErrorHandler.writeErrorDetails(ctx, status.getDetailsList(), jsonGenerator);
        jsonGenerator.flush();
        final String expectedJsonString =
                "[\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\n" +
                "    \"reason\":\"Unknown Exception\",\n" +
                "    \"domain\":\"test\",\n" +
                "    \"metadata\":{\n" +
                "      \"key\":\"value\"\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\n" +
                "    \"retryDelay\":\"100s\"\n" +
                "  },\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.DebugInfo\",\n" +
                "    \"stackEntries\":[\n" +
                "      \"stack1\",\n" +
                "      \"stack2\"\n" +
                "    ],\n" +
                "    \"detail\":\"debug info\"\n" +
                "  },\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\n" +
                "    \"violations\":[\n" +
                "      {\n" +
                "        \"description\":\"quota\"\n" +
                "      }\n" +
                "    ]\n" +
                "  },\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.PreconditionFailure\",\n" +
                "    \"violations\":[\n" +
                "      {\n" +
                "        \"description\":\"violation\"\n" +
                "      }\n" +
                "    ]\n" +
                "  },\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.BadRequest\",\n" +
                "    \"fieldViolations\":[\n" +
                "      {\n" +
                "        \"description\":\"field violation\"\n" +
                "      }\n" +
                "    ]\n" +
                "  },\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.RequestInfo\",\n" +
                "    \"requestId\":\"requestid\"\n" +
                "  },\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.ResourceInfo\",\n" +
                "    \"description\":\"description\"\n" +
                "  },\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.Help\",\n" +
                "    \"links\":[\n" +
                "      {\n" +
                "        \"description\":\"descrption\"\n" +
                "      }\n" +
                "    ]\n" +
                "  },\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/google.rpc.LocalizedMessage\",\n" +
                "    \"message\":\"message\"\n" +
                "  }\n" +
                ']';
        assertThatJson(mapper.readTree(jsonObjectWriter.toString())).isEqualTo(expectedJsonString);
    }

    @Test
    void convertCustomErrorDetailToJsonNodeTest() throws IOException {
        final AuthError authError = AuthError.newBuilder()
                                             .setCode(401)
                                             .setMessage("Auth error.")
                                             .build();
        final Status status = Status.newBuilder()
                                    .setCode(Code.UNKNOWN.getNumber())
                                    .setMessage("Unknown Exceptions Test")
                                    .addDetails(Any.pack(authError))
                                    .build();
        final StringWriter jsonObjectWriter = new StringWriter();
        final JsonGenerator jsonGenerator = mapper.createGenerator(jsonObjectWriter);
        final MessageMarshaller jsonMarshaller = ERROR_DETAILS_MARSHALLER.toBuilder()
                                                                         .register(authError)
                                                                         .build();
        final JsonUnframedGrpcErrorHandler jsonUnframedGrpcErrorHandler = JsonUnframedGrpcErrorHandler.of(
                UnframedGrpcStatusMappingFunction.of(), jsonMarshaller);
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/test"));
        jsonUnframedGrpcErrorHandler.writeErrorDetails(ctx, status.getDetailsList(), jsonGenerator);
        jsonGenerator.flush();
        final String expectedJsonString =
                "[\n" +
                "  {\n" +
                "    \"@type\":\"type.googleapis.com/armeria.grpc.testing.AuthError\",\n" +
                "    \"code\": 401," +
                "    \"message\": \"Auth error.\"" +
                "  }\n" +
                ']';
        assertThatJson(mapper.readTree(jsonObjectWriter.toString())).isEqualTo(expectedJsonString);
    }

    @Test
    void shouldThrowIOException() throws IOException {
        final Empty empty = Empty.getDefaultInstance();
        final Status status = Status.newBuilder().addDetails(Any.pack(empty)).build();
        final StringWriter jsonObjectWriter = new StringWriter();
        final JsonGenerator jsonGenerator = mapper.createGenerator(jsonObjectWriter);

        final JsonUnframedGrpcErrorHandler jsonUnframedGrpcErrorHandler = JsonUnframedGrpcErrorHandler.of();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/test"));

        assertThatThrownBy(() -> jsonUnframedGrpcErrorHandler.writeErrorDetails(
                ctx, status.getDetailsList(), jsonGenerator)).isInstanceOf(IOException.class);
    }
}
