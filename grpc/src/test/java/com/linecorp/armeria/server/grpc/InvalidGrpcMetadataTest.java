/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.armeria.internal.common.grpc.GrpcTestUtil.REQUEST_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class InvalidGrpcMetadataTest {

    // Valid metadata has even count of binaryValues.
    private static final Metadata validMetadata = InternalMetadata.newMetadata(
            "key1".getBytes(StandardCharsets.US_ASCII),
            "val1".getBytes(StandardCharsets.US_ASCII),
            "key2".getBytes(StandardCharsets.US_ASCII),
            "val2".getBytes(StandardCharsets.US_ASCII)
    );
    private static final String KEY_OF_CORRUPTED_METADATA = "grpc_service_impl_error_key";
    // 'usedNames' is 3, but size of 'binaryValues' is 2.
    // 'usedNames' and size of ('binaryValues'.length/2) must be equal, so this is corrupted metadata.
    private static final Metadata corruptedMetadata =
            InternalMetadata.newMetadata(
                    3,
                    KEY_OF_CORRUPTED_METADATA.getBytes(StandardCharsets.US_ASCII),
                    "grpc_service_impl_error_val".getBytes(StandardCharsets.US_ASCII));

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final ServerInterceptor corruptedServerInterceptor = new ServerInterceptor() {
                @Override
                public <I, O> Listener<I> interceptCall(ServerCall<I, O> call,
                                                        Metadata headers,
                                                        ServerCallHandler<I, O> next) {
                    return next.startCall(new SimpleForwardingServerCall<I, O>(call) {
                        @Override
                        public void close(Status status, Metadata trailers) {
                            super.close(status, corruptedMetadata);
                        }
                    }, headers);
                }
            };
            final ServerImplErrorAtMetadataService service = new ServerImplErrorAtMetadataService();
            sb.service(GrpcService.builder()
                                  .addService(service)
                                  .build());
            sb.serviceUnder("/corruptedInterceptor",
                            GrpcService.builder()
                                       .addService(service)
                                       .intercept(corruptedServerInterceptor)
                                       .build());
            sb.decorator(LoggingService.newDecorator());
        }
    };

    // Normal case of #onError at Unary RPC, but metadata set in server interceptor is corrupted.
    // Client cannot expect corrupted metadata is returned from server.
    @Test
    void clientUnaryCall2ForServerUsingCorruptedInterceptor() throws InterruptedException {
        try (ClientRequestContextCaptor clientCaptor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> {
                final TestServiceBlockingStub client =
                        GrpcClients.builder("http://127.0.0.1:" + server.httpPort())
                                   .pathPrefix("/corruptedInterceptor")
                                   .build(TestServiceBlockingStub.class);
                client.unaryCall2(REQUEST_MESSAGE);
            }).satisfies(cause -> {
                assertThat(Status.fromThrowable(cause).getCode()).isEqualTo(Status.INTERNAL.getCode());
                assertThat(Status.trailersFromThrowable(cause)).satisfies(metadata -> {
                    assertThat(metadata.keys()).doesNotContain(KEY_OF_CORRUPTED_METADATA);
                });
            });

            // Test from client side viewpoint.
            final RequestLog log = clientCaptor.get().log().whenComplete().join();
            assertThat(log.responseStatus()).isEqualTo(HttpStatus.OK);
            assertThat(log.responseTrailers().get(GrpcHeaderNames.GRPC_STATUS)).isNull();
            assertThat(log.responseHeaders().get(GrpcHeaderNames.GRPC_STATUS)).satisfies(grpcStatus -> {
                assertThat(grpcStatus).isNotNull();
                assertThat(grpcStatus).isEqualTo(String.valueOf(Status.INTERNAL.getCode().value()));
            });
            assertThat(log.responseCause())
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(cause -> {
                        assertThat(Status.fromThrowable(cause).getCode()).isEqualTo(
                                Status.INTERNAL.getCode());
                        assertThat(Status.trailersFromThrowable(cause)).satisfies(metadata -> {
                            assertThat(metadata.keys()).doesNotContain(KEY_OF_CORRUPTED_METADATA);
                        });
                    });

            // Test from server side viewpoint.
            final ServiceRequestContextCaptor serviceCaptor = server.requestContextCaptor();
            assertThat(serviceCaptor.size()).isEqualTo(1);
            final ServiceRequestContext serviceCtx = serviceCaptor.take();
            assertThat(serviceCtx.log().whenComplete().join().responseCause())
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(cause -> {
                        assertThat(Status.fromThrowable(cause).getCode()).isEqualTo(
                                Status.ABORTED.getCode());
                        assertThat(Status.trailersFromThrowable(cause)).satisfies(metadata -> {
                            // metadata.keys() throws IndexOutOfBoundsException as metadata is corrupted.
                            assertThat(metadata.containsKey(
                                    InternalMetadata.keyOf(KEY_OF_CORRUPTED_METADATA,
                                                           Metadata.ASCII_STRING_MARSHALLER)
                            )).isTrue();
                        });
                    });
        }
    }

    // Normal case of #onError at Unary RPC.
    @Test
    void clientUnaryCall2() throws InterruptedException {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> {
                final TestServiceBlockingStub client =
                        GrpcClients.newClient("http://127.0.0.1:" + server.httpPort(),
                                              TestServiceBlockingStub.class);
                client.unaryCall2(REQUEST_MESSAGE);
            }).satisfies(cause -> {
                assertThat(Status.fromThrowable(cause).getCode()).isEqualTo(Status.ABORTED.getCode());
                assertThat(Status.trailersFromThrowable(cause)).satisfies(metadata -> {
                    for (String key : validMetadata.keys()) {
                        final Key<String> metaKey = Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
                        assertThat(metadata.get(metaKey)).isEqualTo(validMetadata.get(metaKey));
                    }
                });
            });

            final RequestLog log = captor.get().log().whenComplete().join();
            assertThat(log.responseStatus()).isEqualTo(HttpStatus.OK);
            assertThat(log.responseTrailers().get(GrpcHeaderNames.GRPC_STATUS)).isNull();
            assertThat(log.responseHeaders().get(GrpcHeaderNames.GRPC_STATUS)).satisfies(grpcStatus -> {
                assertThat(grpcStatus).isNotNull();
                assertThat(grpcStatus).isEqualTo(String.valueOf(Status.ABORTED.getCode().value()));
            });
            assertThat(log.responseCause())
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(cause -> {
                        assertThat(Status.fromThrowable(cause).getCode()).isEqualTo(Status.ABORTED.getCode());
                        assertThat(Status.trailersFromThrowable(cause)).satisfies(metadata -> {
                            for (String key : validMetadata.keys()) {
                                final Key<String> metaKey = Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
                                assertThat(metadata.get(metaKey)).isEqualTo(validMetadata.get(metaKey));
                            }
                        });
                    });
        }
    }

    // Error inside #onError at Unary RPC.
    // Client cannot expect corrupted metadata is returned from server.
    @Test
    void clientUnaryCall() throws InterruptedException {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> {
                final TestServiceBlockingStub client =
                        GrpcClients.newClient("http://127.0.0.1:" + server.httpPort(),
                                              TestServiceBlockingStub.class);

                client.unaryCall(REQUEST_MESSAGE);
            }).satisfies(cause -> {
                assertThat(Status.fromThrowable(cause).getCode()).isEqualTo(Status.INTERNAL.getCode());
                assertThat(Status.trailersFromThrowable(cause)).isNotEqualTo(corruptedMetadata);
            });

            final RequestLog log = captor.get().log().whenComplete().join();
            assertThat(log.responseStatus()).isEqualTo(HttpStatus.OK);
            assertThat(log.responseTrailers().get(GrpcHeaderNames.GRPC_STATUS)).isNull();
            assertThat(log.responseHeaders().get(GrpcHeaderNames.GRPC_STATUS)).satisfies(grpcStatus -> {
                assertThat(grpcStatus).isNotNull();
                assertThat(grpcStatus).isEqualTo(String.valueOf(Status.INTERNAL.getCode().value()));
            });
            assertThat(log.responseCause())
                    .satisfies(cause -> {
                        assertThat(cause).isInstanceOf(StatusRuntimeException.class);
                        assertThat(Status.fromThrowable(cause).getCode()).isEqualTo(
                                Status.INTERNAL.getCode());
                        assertThat(Status.trailersFromThrowable(cause)).satisfies(metadata -> {
                            assertThat(metadata.keys()).doesNotContain(KEY_OF_CORRUPTED_METADATA);
                        });
                    });
        }
    }

    // Error inside #onError at server streaming RPC
    // Client cannot expect corrupted metadata is returned from server.
    @Test
    void clientUnaryCallServerStreamingOutputCall() throws InterruptedException {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> {
                final TestServiceBlockingStub client =
                        GrpcClients.newClient(
                                "http://127.0.0.1:" + server.httpPort(),
                                TestServiceBlockingStub.class);
                final Iterator<StreamingOutputCallResponse> it = client.streamingOutputCall(
                        StreamingOutputCallRequest.newBuilder().build());
                while (it.hasNext()) {
                    it.next();
                }
            }).satisfies(cause -> {
                assertThat(Status.fromThrowable(cause).getCode()).isEqualTo(
                        Status.INTERNAL.getCode());
                assertThat(Status.trailersFromThrowable(cause)).isNotEqualTo(corruptedMetadata);
            });

            final RequestLog log = captor.get().log().whenComplete().join();
            assertThat(log.responseStatus()).isEqualTo(HttpStatus.OK);
            assertThat(log.responseHeaders().get(GrpcHeaderNames.GRPC_STATUS)).satisfies(grpcStatus -> {
                assertThat(grpcStatus).isEqualTo(String.valueOf(Status.INTERNAL.getCode().value()));
            });
            assertThat(log.responseTrailers().get(GrpcHeaderNames.GRPC_STATUS)).satisfies(grpcStatus -> {
                assertThat(grpcStatus).isNull();
            });
            assertThat(log.responseCause())
                    .isInstanceOf(StatusRuntimeException.class)
                    .satisfies(cause -> {
                        assertThat(Status.fromThrowable(cause).getCode()).isEqualTo(
                                Status.INTERNAL.getCode());
                        assertThat(Status.trailersFromThrowable(cause)).satisfies(metadata -> {
                            assertThat(metadata.keys()).doesNotContain(KEY_OF_CORRUPTED_METADATA);
                        });
                    });
        }
    }

    private static final class ServerImplErrorAtMetadataService extends TestServiceGrpc.TestServiceImplBase {
        // blocking client
        // No error happens when serializing metadata.
        @Override
        public void unaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(new StatusRuntimeException(Status.ABORTED, validMetadata));
        }

        // blocking client
        // Error happens when serializing metadata.
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(new StatusRuntimeException(Status.ABORTED, corruptedMetadata));
        }

        // blocking client
        // Error happens when serializing metadata.
        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            responseObserver.onError(
                    new StatusRuntimeException(Status.FAILED_PRECONDITION, corruptedMetadata));
        }
    }
}
