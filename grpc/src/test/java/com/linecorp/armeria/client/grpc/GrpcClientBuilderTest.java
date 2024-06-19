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

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
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
        final GrpcExceptionHandlerFunction noopExceptionHandler = (ctx, cause, metadata) -> null;
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
}
