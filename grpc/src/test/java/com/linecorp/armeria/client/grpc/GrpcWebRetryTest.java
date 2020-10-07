/*
 * Copyright 2020 LINE Corporation
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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.GrpcWebTrailers;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.grpc.testing.Messages.CompressionType;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

class GrpcWebRetryTest {

    private static final AtomicInteger retryCounter = new AtomicInteger();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .build());
        }
    };

    RetryRuleWithContent<HttpResponse> ruleWithContent;

    @BeforeEach
    void setUp() {
        retryCounter.set(0);
        ruleWithContent =
                RetryRuleWithContent.<HttpResponse>builder()
                        .onResponseHeaders((ctx, headers) -> {
                            // Trailers may be sent together with response headers, with no message in the body.
                            final Integer grpcStatus = headers.getInt(GrpcHeaderNames.GRPC_STATUS);
                            return grpcStatus != null && grpcStatus != 0;
                        })
                        .onResponse((ctx, res) -> res.aggregate().thenApply(aggregatedRes -> {
                            final HttpHeaders trailers = GrpcWebTrailers.get(ctx);
                            return trailers != null && trailers.getInt(GrpcHeaderNames.GRPC_STATUS, -1) != 0;
                        }))
                        .thenBackoff();
    }

    @ArgumentsSource(GrpcSerializationFormatArgumentSource.class)
    @ParameterizedTest
    void unaryCall(SerializationFormat serializationFormat) {
        unaryCall(serializationFormat, SimpleRequest.getDefaultInstance());
    }

    private void unaryCall(SerializationFormat serializationFormat, SimpleRequest request) {
        final TestServiceBlockingStub client =
                Clients.builder(server.uri(SessionProtocol.H1C, serializationFormat))
                       .decorator(RetryingClient.newDecorator(ruleWithContent))
                       .build(TestServiceBlockingStub.class);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SimpleResponse result = client.unaryCall(request);
            assertThat(result.getUsername()).isEqualTo("my name");
            final RequestLog log = captor.get().log().whenComplete().join();
            assertThat(log.children()).hasSize(3);
            log.children().forEach(child -> {
                final ResponseHeaders responseHeaders = child.ensureComplete().responseHeaders();
                assertThat(responseHeaders.contentType()).isSameAs(serializationFormat.mediaType());
            });
        }
    }

    @ArgumentsSource(GrpcSerializationFormatArgumentSource.class)
    @ParameterizedTest
    void unaryCallCompressedResponse(SerializationFormat serializationFormat) {
        unaryCall(serializationFormat, SimpleRequest.newBuilder()
                                                    .setResponseCompression(CompressionType.GZIP)
                                                    .build());
    }

    @ArgumentsSource(GrpcSerializationFormatArgumentSource.class)
    @ParameterizedTest
    void emptyCall(SerializationFormat serializationFormat) {
        final TestServiceBlockingStub client =
                Clients.builder(server.uri(SessionProtocol.H1C, serializationFormat))
                       .decorator(RetryingClient.newDecorator(ruleWithContent))
                       .build(TestServiceBlockingStub.class);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final Empty result = client.emptyCall(Empty.newBuilder().build());
            assertThat(result).isEqualTo(Empty.getDefaultInstance());
            final RequestLog log = captor.get().log().whenComplete().join();
            assertThat(log.children()).hasSize(3);
            log.children().forEach(child -> {
                final ResponseHeaders responseHeaders = child.ensureComplete().responseHeaders();
                assertThat(responseHeaders.contentType()).isSameAs(serializationFormat.mediaType());
            });
        }
    }

    private static class GrpcSerializationFormatArgumentSource implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            return GrpcSerializationFormats.values().stream()
                                           .filter(GrpcSerializationFormats::isGrpcWeb)
                                           .map(Arguments::of);
        }
    }

    private static class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            switch (retryCounter.getAndIncrement()) {
                case 0:
                    responseObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                case 1:
                    responseObserver.onNext(Empty.getDefaultInstance());
                    responseObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                default:
                    responseObserver.onNext(Empty.getDefaultInstance());
                    responseObserver.onCompleted();
                    break;
            }
        }

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final ServerCallStreamObserver<SimpleResponse> serverCallStreamObserver =
                    (ServerCallStreamObserver<SimpleResponse>) responseObserver;
            if (request.getResponseCompression() == CompressionType.GZIP) {
                serverCallStreamObserver.setCompression("gzip");
            }
            switch (retryCounter.getAndIncrement()) {
                case 0:
                    serverCallStreamObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                case 1:
                    serverCallStreamObserver.onNext(SimpleResponse.newBuilder().setUsername("my name").build());
                    serverCallStreamObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                default:
                    serverCallStreamObserver.onNext(SimpleResponse.newBuilder().setUsername("my name").build());
                    serverCallStreamObserver.onCompleted();
                    break;
            }
        }
    }
}
