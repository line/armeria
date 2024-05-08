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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;
import com.google.rpc.Status;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class JsonUnframedGrpcErrorHandlerTest {

    @RegisterExtension
    static ServerExtension jsonResServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, false,
                            JsonUnframedGrpcErrorHandler.of(),
                            testServiceGrpcStatus);
        }
    };

    @RegisterExtension
    static ServerExtension jsonResServerWithCustomStatusMapping = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final UnframedGrpcStatusMappingFunction mappingFunction = (ctx, status, response) -> HttpStatus.OK;
            configureServer(sb, false,
                            JsonUnframedGrpcErrorHandler.of(mappingFunction),
                            testServiceGrpcStatus);
        }
    };

    private final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private static class TestServiceGrpcStatus extends TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            final ErrorInfo errorInfo = ErrorInfo.newBuilder()
                                                 .setDomain("test")
                                                 .setReason("Unknown Exception").build();

            final Status status = Status.newBuilder()
                                        .setCode(Code.UNKNOWN.getNumber())
                                        .setMessage("Unknown Exceptions Test")
                                        .addDetails(Any.pack(errorInfo))
                                        .build();

            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }

    private static final TestServiceGrpcStatus testServiceGrpcStatus = new TestServiceGrpcStatus();

    private static void configureServer(ServerBuilder sb, boolean verboseResponses,
                                        UnframedGrpcErrorHandler errorHandler,
                                        TestServiceImplBase testServiceImplBase) {
        sb.verboseResponses(verboseResponses);
        sb.service(GrpcService.builder()
                              .addService(testServiceImplBase)
                              .enableUnframedRequests(true)
                              .unframedGrpcErrorHandler(errorHandler)
                              .build());
    }

    @Test
    void jsonResponse() throws JsonProcessingException {
        final BlockingWebClient client = jsonResServer.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThatJson(mapper.readTree(response.contentUtf8()))
                .isEqualTo(
                        '{' +
                        "  \"code\": 2," +
                        "  \"grpc-code\": \"UNKNOWN\"," +
                        "  \"message\": \"Unknown Exceptions Test\"," +
                        "  \"details\": [" +
                        "    {" +
                        "      \"@type\": \"type.googleapis.com/google.rpc.ErrorInfo\"," +
                        "      \"reason\": \"Unknown Exception\"," +
                        "      \"domain\": \"test\"" +
                        "    }" +
                        "  ]" +
                        '}');
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void jsonResponseWithCustomStatusMapping() throws JsonProcessingException {
        final BlockingWebClient client = jsonResServerWithCustomStatusMapping.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(mapper.readTree(response.contentUtf8()))
                .isEqualTo(
                        '{' +
                        "  \"code\": 2," +
                        "  \"grpc-code\": \"UNKNOWN\"," +
                        "  \"message\": \"Unknown Exceptions Test\"," +
                        "  \"details\": [" +
                        "    {" +
                        "      \"@type\": \"type.googleapis.com/google.rpc.ErrorInfo\"," +
                        "      \"reason\": \"Unknown Exception\"," +
                        "      \"domain\": \"test\"" +
                        "    }" +
                        "  ]" +
                        '}');
        assertThat(response.trailers()).isEmpty();
    }
}
