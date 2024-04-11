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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.grpc.testing.Error.AuthError;
import com.linecorp.armeria.grpc.testing.Error.InternalError;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

public class UnframedGrpcErrorHandlerTest {
    @RegisterExtension
    static ServerExtension nonVerboseServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, false, UnframedGrpcErrorHandler.of(), testService);
        }
    };

    @RegisterExtension
    static ServerExtension verbosePlaintextResServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, true, UnframedGrpcErrorHandler.ofPlainText(), testService);
        }
    };

    @RegisterExtension
    static ServerExtension verboseJsonResServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, true, UnframedGrpcErrorHandler.ofJson(), testService);
        }
    };

    @RegisterExtension
    static ServerExtension testServerGrpcStatus = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, false, UnframedGrpcErrorHandler.ofJson(), testServiceGrpcStatus);
        }
    };

    @RegisterExtension
    static ServerExtension plaintextResServerWithBuilder = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, false,
                            UnframedGrpcErrorHandler.builder()
                                                    .build(),
                            testService);
        }
    };

    @RegisterExtension
    static ServerExtension jsonResServerWithMarshalledMessage = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, false,
                            UnframedGrpcErrorHandler.builder()
                                                    .registerMarshalledMessageTypes(InternalError.class)
                                                    .registerMarshalledMessages(AuthError.newBuilder().build())
                                                    .responseTypes(UnframedGrpcErrorResponseType.JSON)
                                                    .build(),
                            testServiceWithCustomMessage);
        }
    };

    @RegisterExtension
    static ServerExtension jsonResServerWithCustomJsonMarshaller = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final MessageMarshaller jsonMarshaller = MessageMarshaller.builder()
                                                                      .register(InternalError.class)
                                                                      .register(AuthError.class)
                                                                      .build();
            configureServer(sb, false,
                            UnframedGrpcErrorHandler.builder()
                                                    .jsonMarshaller(jsonMarshaller)
                                                    .responseTypes(UnframedGrpcErrorResponseType.JSON)
                                                    .build(),
                            testServiceWithCustomMessage);
        }
    };

    @RegisterExtension
    static ServerExtension plaintextResServerWithCustomStatusMapping = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final UnframedGrpcStatusMappingFunction mappingFunction = (ctx, status, response) -> HttpStatus.OK;
            configureServer(sb, false,
                            UnframedGrpcErrorHandler.builder()
                                                    .statusMappingFunction(mappingFunction)
                                                    .build(),
                            testService);
        }
    };

    private static class TestService extends TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            throw Status.UNKNOWN.withDescription("grpc error message").asRuntimeException();
        }
    }

    private static class TestServiceGrpcStatus extends TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            final ErrorInfo errorInfo = ErrorInfo.newBuilder()
                                                 .setDomain("test")
                                                 .setReason("Unknown Exception").build();

            final com.google.rpc.Status
                    status = com.google.rpc.Status.newBuilder()
                                                  .setCode(Code.UNKNOWN.getNumber())
                                                  .setMessage("Unknown Exceptions Test")
                                                  .addDetails(Any.pack(errorInfo))
                                                  .build();

            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }

    private static class TestServiceWithCustomMessage extends TestServiceImplBase {

        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            final InternalError internalError = InternalError.newBuilder()
                                                             .setCode(500)
                                                             .setMessage("Internal server error.")
                                                             .build();

            final AuthError authError = AuthError.newBuilder()
                                                 .setCode(500)
                                                 .setMessage("Auth server error.")
                                                 .build();

            final com.google.rpc.Status
                    status = com.google.rpc.Status.newBuilder()
                                                  .setCode(Code.INTERNAL.getNumber())
                                                  .setMessage("Custom error message test.")
                                                  .addDetails(Any.pack(internalError))
                                                  .addDetails(Any.pack(authError))
                                                  .build();

            responseObserver.onError(StatusProto.toStatusRuntimeException(status));
        }
    }

    private final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private static final TestService testService = new TestService();

    private static final TestServiceGrpcStatus testServiceGrpcStatus = new TestServiceGrpcStatus();

    private static final TestServiceWithCustomMessage testServiceWithCustomMessage =
            new TestServiceWithCustomMessage();

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
    void withoutStackTrace() {
        final BlockingWebClient client = nonVerboseServer.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final String content = response.contentUtf8();
        assertThat(content).isEqualTo("grpc-code: UNKNOWN, grpc error message");
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void plaintextWithStackTrace() {
        final BlockingWebClient client = verbosePlaintextResServer.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final String content = response.contentUtf8();
        assertThat(content).startsWith("grpc-code: UNKNOWN, grpc error message" +
                                       "\nstack-trace:\nio.grpc.StatusRuntimeException");
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void jsonWithStackTrace() {
        final BlockingWebClient client = verboseJsonResServer.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final String content = response.contentUtf8();
        assertThat(content).startsWith("{\"code\":2,\"grpc-code\":\"UNKNOWN\"," +
                                       "\"message\":\"grpc error message\"," +
                                       "\"stack-trace\":\"io.grpc.StatusRuntimeException");
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void richJson() throws JsonProcessingException {
        final BlockingWebClient client = testServerGrpcStatus.webClient().blocking();
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
    void plainTestUsingBuilder() {
        final BlockingWebClient client = plaintextResServerWithBuilder.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        final String content = response.contentUtf8();
        assertThat(content).isEqualTo("grpc-code: UNKNOWN, grpc error message");
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void jsonWithCustomMessage() throws JsonProcessingException {
        final BlockingWebClient client = jsonResServerWithMarshalledMessage.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThatJson(mapper.readTree(response.contentUtf8()))
                .isEqualTo(
                        '{' +
                        "  \"code\": 13," +
                        "  \"grpc-code\": \"INTERNAL\"," +
                        "  \"message\": \"Custom error message test.\"," +
                        "  \"details\": [" +
                        "    {" +
                        "      \"@type\": \"type.googleapis.com/armeria.grpc.testing.InternalError\"," +
                        "      \"code\": 500," +
                        "      \"message\": \"Internal server error.\"" +
                        "    }," +
                        "    {" +
                        "      \"@type\": \"type.googleapis.com/armeria.grpc.testing.AuthError\"," +
                        "      \"code\": 500," +
                        "      \"message\": \"Auth server error.\"" +
                        "    }" +
                        "  ]" +
                        '}');
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void jsonWithCustomJsonMarshaller() throws JsonProcessingException {
        final BlockingWebClient client = jsonResServerWithCustomJsonMarshaller.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThatJson(mapper.readTree(response.contentUtf8()))
                .isEqualTo(
                        '{' +
                        "  \"code\": 13," +
                        "  \"grpc-code\": \"INTERNAL\"," +
                        "  \"message\": \"Custom error message test.\"," +
                        "  \"details\": [" +
                        "    {" +
                        "      \"@type\": \"type.googleapis.com/armeria.grpc.testing.InternalError\"," +
                        "      \"code\": 500," +
                        "      \"message\": \"Internal server error.\"" +
                        "    }," +
                        "    {" +
                        "      \"@type\": \"type.googleapis.com/armeria.grpc.testing.AuthError\"," +
                        "      \"code\": 500," +
                        "      \"message\": \"Auth server error.\"" +
                        "    }" +
                        "  ]" +
                        '}');
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void plaintextWithCustomStatusMapping() {
        final BlockingWebClient client = plaintextResServerWithCustomStatusMapping.webClient().blocking();
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(MediaType.PROTOBUF, Empty.getDefaultInstance().toByteArray())
                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("grpc-code: UNKNOWN, grpc error message");
        assertThat(response.trailers()).isEmpty();
    }
}
