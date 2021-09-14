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

package com.linecorp.armeria.client.grpc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.grpc.protocol.GrpcWebTrailers;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.internal.common.grpc.protocol.StatusCodes;
import com.linecorp.armeria.internal.common.grpc.protocol.UnaryGrpcSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;

class UnaryGrpcClientTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new TestService())
                                  .supportedSerializationFormats(UnaryGrpcSerializationFormats.values())
                                  .build());
        }
    };

    private static SimpleRequest buildRequest(String payload) {
        return SimpleRequest.newBuilder()
                            .setPayload(Payload.newBuilder()
                                               .setBody(ByteString.copyFromUtf8(payload))
                                               .build())
                            .build();
    }

    private static String getUri(SerializationFormat serializationFormat) {
        return String.format("%s+%s", serializationFormat, server.httpUri());
    }

    @ParameterizedTest
    @ArgumentsSource(UnsupportedGrpcSerializationFormatArgumentsProvider.class)
    void unsupportedSerializationFormat(SerializationFormat serializationFormat) {
        assertThrows(AssertionError.class,
                     () -> Clients.newClient(getUri(serializationFormat), UnaryGrpcClient.class));
    }

    @ParameterizedTest
    @ArgumentsSource(UnaryGrpcSerializationFormatArgumentsProvider.class)
    void normal(SerializationFormat serializationFormat) throws Exception {
        final UnaryGrpcClient client = Clients.newClient(getUri(serializationFormat), UnaryGrpcClient.class);
        final SimpleRequest request = buildRequest("hello");

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final byte[] responseBytes =
                    client.execute("/armeria.grpc.testing.TestService/UnaryCall", request.toByteArray()).join();
            final ClientRequestContext ctx = captor.get();
            final HttpHeaders trailers = GrpcWebTrailers.get(ctx);
            assertThat(trailers).isNotNull();
            assertThat(trailers.getInt(GrpcHeaderNames.GRPC_STATUS)).isZero();
            final SimpleResponse response = SimpleResponse.parseFrom(responseBytes);
            assertThat(response.getPayload().getBody().toStringUtf8()).isEqualTo("hello");
        }
    }

    /** This shows we can handle status that happens in headers. */
    @ParameterizedTest
    @ArgumentsSource(UnaryGrpcSerializationFormatArgumentsProvider.class)
    void statusException(SerializationFormat serializationFormat) {
        final UnaryGrpcClient client = Clients.newClient(getUri(serializationFormat), UnaryGrpcClient.class);
        final SimpleRequest request = buildRequest("peanuts");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(
                    () -> client.execute("/armeria.grpc.testing.TestService/UnaryCall",
                                         request.toByteArray()).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ArmeriaStatusException.class)
                    .hasMessageContaining("we don't sell peanuts");
            final ClientRequestContext ctx = captor.get();
            final HttpHeaders trailers = GrpcWebTrailers.get(ctx);
            assertThat(trailers).isNotNull();
            assertThat(trailers.getInt(GrpcHeaderNames.GRPC_STATUS)).isEqualTo(StatusCodes.INTERNAL);
        }
    }

    /** This shows we can handle status that happens in trailers. */
    @ParameterizedTest
    @ArgumentsSource(UnaryGrpcSerializationFormatArgumentsProvider.class)
    void lateStatusException(SerializationFormat serializationFormat) {
        final UnaryGrpcClient client = Clients.newClient(getUri(serializationFormat), UnaryGrpcClient.class);
        final SimpleRequest request = buildRequest("ice cream");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(
                    () -> client.execute("/armeria.grpc.testing.TestService/UnaryCall",
                                         request.toByteArray()).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ArmeriaStatusException.class)
                    .hasMessageContaining("no more ice cream");
            final ClientRequestContext ctx = captor.get();
            final HttpHeaders trailers = GrpcWebTrailers.get(ctx);
            assertThat(trailers).isNotNull();
            assertThat(trailers.getInt(GrpcHeaderNames.GRPC_STATUS)).isEqualTo(StatusCodes.INTERNAL);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(UnaryGrpcSerializationFormatArgumentsProvider.class)
    void invalidPayload(SerializationFormat serializationFormat) {
        final UnaryGrpcClient client = Clients.newClient(getUri(serializationFormat), UnaryGrpcClient.class);
        assertThatThrownBy(
                () -> client.execute("/armeria.grpc.testing.TestService/UnaryCall",
                                     "foobarbreak".getBytes(StandardCharsets.UTF_8)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ArmeriaStatusException.class);
    }

    @ParameterizedTest
    @ArgumentsSource(GrpcWebUnaryGrpcSerializationFormatArgumentsProvider.class)
    void errorHandlingForGrpcWeb(SerializationFormat serializationFormat) {
        final UnaryGrpcClient client = Clients.newClient(getUri(serializationFormat), UnaryGrpcClient.class);
        final SimpleRequest request = buildRequest("two ice creams");
        assertThatThrownBy(
                () -> client.execute("/armeria.grpc.testing.TestService/UnaryCall",
                                     request.toByteArray()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ArmeriaStatusException.class)
                .hasMessageContaining(
                        "received more than one data message; UnaryGrpcClient does not support streaming.");
    }

    private static class TestService extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final SimpleResponse response = SimpleResponse.newBuilder()
                                                          .setPayload(request.getPayload())
                                                          .build();
            final String payload = request.getPayload().getBody().toStringUtf8();
            if ("peanuts".equals(payload)) {
                responseObserver.onError(
                        new StatusException(Status.INTERNAL.withDescription("we don't sell peanuts"))
                );
            } else if ("ice cream".equals(payload)) {
                responseObserver.onNext(response); // Note: we error after the response, so trailers
                responseObserver.onError(
                        new StatusException(Status.INTERNAL.withDescription("no more ice cream"))
                );
            } else if ("two ice creams".equals(payload)) {
                responseObserver.onNext(response);
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } else {
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
        }
    }

    private static class UnaryGrpcSerializationFormatArgumentsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            return UnaryGrpcSerializationFormats.values().stream().map(Arguments::of);
        }
    }

    private static class GrpcWebUnaryGrpcSerializationFormatArgumentsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            return UnaryGrpcSerializationFormats.values().stream().filter(
                    UnaryGrpcSerializationFormats::isGrpcWeb).map(Arguments::of);
        }
    }

    private static class UnsupportedGrpcSerializationFormatArgumentsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            return GrpcSerializationFormats.values().stream().filter(
                    s -> !UnaryGrpcSerializationFormats.values().contains(s)).map(Arguments::of);
        }
    }
}
