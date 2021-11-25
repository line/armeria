/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server.grpc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.GrpcWebTrailers;
import com.linecorp.armeria.grpc.testing.Messages;
import com.linecorp.armeria.grpc.testing.Messages.EchoStatus;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;
import com.linecorp.armeria.internal.common.grpc.protocol.UnaryGrpcSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

class AbstractUnaryGrpcServiceTest {
    private static final String METHOD_NAME = "/armeria.grpc.testing.TestService/UnaryCall";
    private static final String PAYLOAD_BODY = "hello";

    private static final SimpleRequest REQUEST_MESSAGE = SimpleRequest.newBuilder().setPayload(
            Payload.newBuilder().setBody(ByteString.copyFromUtf8(PAYLOAD_BODY)).build()).build();
    private static final SimpleResponse RESPONSE_MESSAGE = SimpleResponse.newBuilder().setPayload(
            Payload.newBuilder().setBody(ByteString.copyFromUtf8(PAYLOAD_BODY)).build()).build();

    private static final SimpleRequest EXCEPTION_REQUEST_MESSAGE =
            SimpleRequest.newBuilder()
                         .setResponseStatus(EchoStatus.newBuilder()
                                                      .setCode(StatusCodes.PERMISSION_DENIED)
                                                      .setMessage("not for your eyes")
                                                      .build())
                         .build();

    // This service only depends on protobuf. Users can use a custom decoder / encoder to avoid even that.
    private static class TestService extends AbstractUnaryGrpcService {

        @Override
        protected CompletionStage<byte[]> handleMessage(ServiceRequestContext ctx, byte[] message) {
            assertThat(ServiceRequestContext.currentOrNull()).isSameAs(ctx);

            final SimpleRequest request;
            try {
                request = SimpleRequest.parseFrom(message);
            } catch (InvalidProtocolBufferException e) {
                throw new UncheckedIOException(e);
            }
            if (request.hasResponseStatus()) {
                // For statusExceptionUpstream() and statusExceptionDownstream()
                assertThat(request).isEqualTo(EXCEPTION_REQUEST_MESSAGE);
                final Messages.EchoStatus resStatus = request.getResponseStatus();
                throw new ArmeriaStatusException(resStatus.getCode(), resStatus.getMessage());
            } else {
                // For normalUpstream() and normalDownstream()
                assertThat(request).isEqualTo(REQUEST_MESSAGE);
                return CompletableFuture.completedFuture(RESPONSE_MESSAGE.toByteArray());
            }
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(METHOD_NAME, new TestService());
        }
    };

    private static class UnaryGrpcSerializationFormatArgumentsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            return UnaryGrpcSerializationFormats.values().stream().map(Arguments::of);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UnaryGrpcSerializationFormatArgumentsProvider.class)
    void normalDownstream(SerializationFormat serializationFormat) throws Exception {
        final TestServiceBlockingStub stub =
                Clients.newClient(server.httpUri(serializationFormat),
                                  TestServiceBlockingStub.class);
        final SimpleResponse response = stub.unaryCall(REQUEST_MESSAGE);
        assertThat(response).isEqualTo(RESPONSE_MESSAGE);
        final ServiceRequestContextCaptor captor = server.requestContextCaptor();
        final HttpHeaders trailers = GrpcWebTrailers.get(captor.take());
        assertThat(trailers).isNotNull();
        assertThat(trailers.getInt(GrpcHeaderNames.GRPC_STATUS)).isZero();
    }

    @Test
    void normalUpstream() {
        final ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                                            .usePlaintext()
                                                            .build();
        try {
            final TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel);
            final SimpleResponse response = stub.unaryCall(REQUEST_MESSAGE);
            assertThat(response).isEqualTo(RESPONSE_MESSAGE);
        } finally {
            channel.shutdownNow();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UnaryGrpcSerializationFormatArgumentsProvider.class)
    void statusExceptionDownstream(SerializationFormat serializationFormat) throws Exception {
        final TestServiceBlockingStub stub =
                Clients.newClient(server.httpUri(serializationFormat),
                                  TestServiceBlockingStub.class);
        assertThatThrownBy(() -> stub.unaryCall(EXCEPTION_REQUEST_MESSAGE))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    final Status status = cause.getStatus();
                    assertThat(status.getCode().value()).isEqualTo(StatusCodes.PERMISSION_DENIED);
                    assertThat(status.getDescription()).isEqualTo("not for your eyes");
                });

        final ServiceRequestContextCaptor captor = server.requestContextCaptor();
        final HttpHeaders trailers = GrpcWebTrailers.get(captor.take());
        assertThat(trailers).isNotNull();
        assertThat(trailers.getInt(GrpcHeaderNames.GRPC_STATUS)).isEqualTo(StatusCodes.PERMISSION_DENIED);
    }

    @Test
    void statusExceptionUpstream() {
        final ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                                            .usePlaintext()
                                                            .build();
        try {
            final TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel);
            assertThatThrownBy(() -> stub.unaryCall(EXCEPTION_REQUEST_MESSAGE))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                        final Status status = cause.getStatus();
                        assertThat(status.getCode().value()).isEqualTo(StatusCodes.PERMISSION_DENIED);
                        assertThat(status.getDescription()).isEqualTo("not for your eyes");
                    });
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void unsupportedMediaType() {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse response =
                client.post(METHOD_NAME, "foobarbreak").aggregate().join();

        assertThat(response.headers().get(HttpHeaderNames.STATUS)).isEqualTo(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.codeAsText());
    }

    @ParameterizedTest
    @ArgumentsSource(UnaryGrpcSerializationFormatArgumentsProvider.class)
    void invalidPayload(SerializationFormat serializationFormat) throws Exception {
        final WebClient client = WebClient.of(server.httpUri());

        final AggregatedHttpResponse message = client.prepare().post(METHOD_NAME).content(
                serializationFormat.mediaType(), "foobarbreak").execute().aggregate().join();

        // Trailers-Only response.
        assertThat(message.headers().get(HttpHeaderNames.STATUS)).isEqualTo(HttpStatus.OK.codeAsText());
        assertThat(message.headers().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                serializationFormat.mediaType().toString());
        assertThat(message.headers().get(GrpcHeaderNames.GRPC_STATUS))
                .isEqualTo(Integer.toString(StatusCodes.INTERNAL));
        assertThat(message.headers().get(GrpcHeaderNames.GRPC_MESSAGE)).isNotBlank();
        assertThat(message.content().isEmpty()).isEqualTo(true);
        final ServiceRequestContextCaptor captor = server.requestContextCaptor();
        final HttpHeaders trailers = GrpcWebTrailers.get(captor.take());
        assertThat(trailers).isNotNull();
        assertThat(trailers.getInt(GrpcHeaderNames.GRPC_STATUS)).isEqualTo(StatusCodes.INTERNAL);
    }
}
