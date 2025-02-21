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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static testing.grpc.Messages.PayloadType.COMPRESSABLE;

import java.io.InputStream;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.client.ClientBuilderParamsUtil;
import com.linecorp.armeria.internal.client.endpoint.FailingEndpointGroup;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.PrototypeMarshaller;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class GrpcClientBuilderTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(CommonPools.blockingTaskExecutor()))
                                  .build());
        }
    };

    @Test
    void defaultSerializationFormat() {
        TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com").build(TestServiceBlockingStub.class);
        ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().getScheme())
                .startsWith(GrpcSerializationFormats.PROTO.uriText());

        client = GrpcClients.builder("none+http", EndpointGroup.of()).build(TestServiceBlockingStub.class);
        clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().getScheme())
                .startsWith(GrpcSerializationFormats.PROTO.uriText());
    }

    @Test
    void customSerializationFormat() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("gjson+http://foo.com").build(TestServiceBlockingStub.class);

        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().getScheme())
                .startsWith(GrpcSerializationFormats.JSON.uriText());
    }

    @Test
    void setSerializationFormat() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .serializationFormat(GrpcSerializationFormats.JSON)
                           .build(TestServiceBlockingStub.class);
        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().getScheme())
                .startsWith(GrpcSerializationFormats.JSON.uriText());

        assertThatThrownBy(() -> GrpcClients.builder("http://foo.com")
                                            .serializationFormat(SerializationFormat.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serializationFormat: ");
    }

    @Test
    void invalidSerializationFormat() {
        assertThatThrownBy(() -> GrpcClients.builder("unknown+http://foo.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefix() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .pathPrefix("/bar")
                           .build(TestServiceBlockingStub.class);
        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.uri().toString()).isEqualTo("gproto+http://foo.com/bar/");

        assertThatThrownBy(() -> {
            GrpcClients.builder("http://foo.com")
                       .pathPrefix("bar")
                       .build(TestServiceBlockingStub.class);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("prefix: bar (must start with '/')");
    }

    @Test
    void messageLength() {
        final int maxRequestMessageLength = 10;
        final int maxResponseMessageLength = 20;
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .maxRequestMessageLength(maxRequestMessageLength)
                           .maxResponseMessageLength(maxResponseMessageLength)
                           .build(TestServiceBlockingStub.class);
        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.options().get(GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES))
                .isEqualTo(maxResponseMessageLength);

        assertThat(clientParams.options().get(GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES))
                .isEqualTo(maxRequestMessageLength);
    }

    @Test
    void enableUnsafeWrapResponseBuffers() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .enableUnsafeWrapResponseBuffers(true)
                           .build(TestServiceBlockingStub.class);
        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.options().get(GrpcClientOptions.UNSAFE_WRAP_RESPONSE_BUFFERS)).isTrue();
    }

    @Test
    void canNotSetUseMethodMarshallerAndUnsafeWrapDeserializedBufferAtTheSameTime() {
        assertThatThrownBy(() -> GrpcClients.builder(server.httpUri())
                                            .enableUnsafeWrapResponseBuffers(true)
                                            .useMethodMarshaller(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "'unsafeWrapRequestBuffers' and 'useMethodMarshaller' are mutually exclusive.");
    }

    @ParameterizedTest
    @CsvSource({"true, 1, 1", "false, 0, 0"})
    void useMethodMarshaller(boolean useMethodMarshaller, int expectedStreamCallCnt, int expectedParseCallCnt) {
        final CustomMarshallerInterceptor customMarshallerInterceptor = new CustomMarshallerInterceptor();
        assertThat(customMarshallerInterceptor.getSpiedMarshallerStreamCallCnt()).isEqualTo(0);
        assertThat(customMarshallerInterceptor.getSpiedMarshallerParseCallCnt()).isEqualTo(0);

        final TestServiceBlockingStub stub = GrpcClients.builder(server.httpUri())
                                                        .intercept(customMarshallerInterceptor)
                                                        .useMethodMarshaller(useMethodMarshaller)
                                                        .build(TestServiceBlockingStub.class);
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setResponseSize(1)
                                                   .setResponseType(COMPRESSABLE)
                                                   .setPayload(Payload.newBuilder()
                                                                      .setBody(ByteString.copyFrom(
                                                                              new byte[1])))
                                                   .build();
        stub.unaryCall(request);
        assertThat(customMarshallerInterceptor.getSpiedMarshallerStreamCallCnt()).isEqualTo(
                expectedStreamCallCnt);
        assertThat(customMarshallerInterceptor.getSpiedMarshallerParseCallCnt()).isEqualTo(
                expectedParseCallCnt);
    }

    @Test
    void intercept() {
        final ClientInterceptor interceptorA = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(MethodDescriptor<I, O> method,
                                                         CallOptions callOptions, Channel next) {
                return next.newCall(method, callOptions);
            }
        };

        final ClientInterceptor interceptorB = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(MethodDescriptor<I, O> method,
                                                         CallOptions callOptions, Channel next) {
                return next.newCall(method, callOptions);
            }
        };

        final TestServiceBlockingStub client =
                GrpcClients.builder("http://foo.com")
                           .intercept(interceptorA)
                           .intercept(interceptorB)
                           .build(TestServiceBlockingStub.class);

        final ClientBuilderParams clientParams = Clients.unwrap(client, ClientBuilderParams.class);
        assertThat(clientParams.options().get(GrpcClientOptions.INTERCEPTORS))
                .containsExactly(interceptorA, interceptorB);
    }

    private static class CustomMarshallerInterceptor implements ClientInterceptor {
        private int spiedMarshallerStreamCallCnt;
        private int spiedMarshallerParseCallCnt;

        int getSpiedMarshallerStreamCallCnt() {
            return spiedMarshallerStreamCallCnt;
        }

        int getSpiedMarshallerParseCallCnt() {
            return spiedMarshallerParseCallCnt;
        }

        @Override
        public <I, O> ClientCall<I, O> interceptCall(MethodDescriptor<I, O> method, CallOptions callOptions,
                                                     Channel next) {
            final MethodDescriptor<I, O> methodDescriptor = method.toBuilder().setRequestMarshaller(
                    new PrototypeMarshaller<I>() {
                        @Nullable
                        @Override
                        public I getMessagePrototype() {
                            return null;
                        }

                        @Override
                        public Class<I> getMessageClass() {
                            return null;
                        }

                        @Override
                        public InputStream stream(I value) {
                            spiedMarshallerStreamCallCnt++;
                            return method.getRequestMarshaller().stream(value);
                        }

                        @Override
                        public I parse(InputStream inputStream) {
                            return null;
                        }
                    }).setResponseMarshaller(
                    new PrototypeMarshaller<O>() {
                        @Nullable
                        @Override
                        public O getMessagePrototype() {
                            return (O) SimpleResponse.getDefaultInstance();
                        }

                        @Override
                        public Class<O> getMessageClass() {
                            return null;
                        }

                        @Override
                        public InputStream stream(O o) {
                            return null;
                        }

                        @Override
                        public O parse(InputStream inputStream) {
                            spiedMarshallerParseCallCnt++;
                            return method.parseResponse(inputStream);
                        }
                    }).build();
            return next.newCall(methodDescriptor, callOptions);
        }
    }

    @Test
    void useDefaultGrpcExceptionHandlerFunctionAsFallback() {
        final GrpcExceptionHandlerFunction noopExceptionHandler = (ctx, status, cause, metadata) -> null;
        final GrpcExceptionHandlerFunction exceptionHandler =
                GrpcExceptionHandlerFunction.builder()
                                            .on(ContentTooLargeException.class, noopExceptionHandler)
                                            .build();
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .maxResponseLength(1)
                                                          .exceptionHandler(exceptionHandler)
                                                          .build(TestServiceBlockingStub.class);

        // Fallback exception handler expected to return RESOURCE_EXHAUSTED for the ContentTooLargeException
        assertThatThrownBy(() -> client.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus())
                .extracting(Status::getCode)
                .isEqualTo(Code.RESOURCE_EXHAUSTED);
    }

    @Test
    void undefinedProtocol() {
        assertThatThrownBy(() -> GrpcClients
                .newClient(Scheme.of(GrpcSerializationFormats.PROTO, SessionProtocol.UNDEFINED),
                           Endpoint.of("1.2.3.4"), TestServiceBlockingStub.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one preprocessor must be specified");

        assertThatThrownBy(() -> GrpcClients
                .newClient("undefined://1.2.3.4", TestServiceBlockingStub.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one preprocessor must be specified");
    }

    public static Stream<Arguments> preprocessor_args() {
        final HttpPreprocessor preprocessor = HttpPreprocessor.of(SessionProtocol.HTTP, server.httpEndpoint());
        return Stream.of(
                Arguments.of(GrpcClients.newClient(preprocessor, TestServiceBlockingStub.class)),
                Arguments.of(GrpcClients.newClient(GrpcSerializationFormats.PROTO,
                                                   preprocessor, TestServiceBlockingStub.class)),
                Arguments.of(GrpcClients.builder(GrpcSerializationFormats.PROTO,
                                                   preprocessor)
                                        .build(TestServiceBlockingStub.class)),
                Arguments.of(GrpcClients.builder(preprocessor)
                                        .build(TestServiceBlockingStub.class)),
                Arguments.of(GrpcClients.builder(PreClient::execute)
                                        .preprocessor(preprocessor)
                                        .build(TestServiceBlockingStub.class))
        );
    }

    @ParameterizedTest
    @MethodSource("preprocessor_args")
    void preprocessor(TestServiceBlockingStub stub) {
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(stub.emptyCall(Empty.getDefaultInstance())).isEqualTo(Empty.getDefaultInstance());
            ctx = captor.get();
        }
        final ClientOptionValue<Long> option = ClientOptions.WRITE_TIMEOUT_MILLIS.newValue(Long.MAX_VALUE);
        final TestServiceBlockingStub derivedStub = Clients.newDerivedClient(stub, option);
        final ClientRequestContext derivedCtx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(derivedStub.emptyCall(Empty.getDefaultInstance())).isEqualTo(Empty.getDefaultInstance());
            derivedCtx = captor.get();
        }
        assertThat(ctx.options().clientPreprocessors().preprocessors())
                .isEqualTo(derivedCtx.options().clientPreprocessors().preprocessors());
    }

    public static Stream<Arguments> preprocessParams_args() {
        return Stream.of(
                Arguments.of(GrpcClients.newClient(PreClient::execute,
                                                   TestServiceBlockingStub.class).getChannel(), "/"),
                Arguments.of(GrpcClients.builder(PreClient::execute)
                                        .pathPrefix("/prefix")
                                        .build(TestServiceBlockingStub.class).getChannel(), "/prefix/")
        );
    }

    @ParameterizedTest
    @MethodSource("preprocessParams_args")
    void preprocessParams(ClientBuilderParams params, String expectedPrefix) {
        assertThat(params.scheme()).isEqualTo(Scheme.of(GrpcSerializationFormats.PROTO,
                                                        SessionProtocol.UNDEFINED));
        assertThat(params.endpointGroup()).isInstanceOf(FailingEndpointGroup.class);
        assertThat(params.absolutePathRef()).isEqualTo(expectedPrefix);
        assertThat(params.uri().getRawAuthority()).startsWith("armeria-preprocessor");
        assertThat(params.uri().getScheme()).isEqualTo("gproto+undefined");
        assertThat(ClientBuilderParamsUtil.isInternalUri(params.uri())).isTrue();
        assertThat(Clients.isUndefinedUri(params.uri())).isFalse();
    }

    @Test
    void preprocessorThrows() {
        final GrpcClientBuilder builder = GrpcClients.builder("http://foo.com");
        assertThatThrownBy(() -> builder.rpcPreprocessor(RpcPreprocessor.of(SessionProtocol.HTTP,
                                                                            Endpoint.of("foo.com"))))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("rpcPreprocessor() does not support gRPC");

        final RpcPreprocessor rpcPreprocessor =
                RpcPreprocessor.of(SessionProtocol.HTTP, Endpoint.of("foo.com"));
        assertThatThrownBy(() -> Clients.newClient(GrpcSerializationFormats.PROTO,
                                                   ClientPreprocessors.ofRpc(rpcPreprocessor),
                                                   TestServiceBlockingStub.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one preprocessor must be specified");
    }
}
