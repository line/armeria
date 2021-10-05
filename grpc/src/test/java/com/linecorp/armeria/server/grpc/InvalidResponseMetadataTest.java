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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.ForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

class InvalidResponseMetadataTest {
    private static final ExceptionalService testService = new ExceptionalService();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(LoggingService.newDecorator());
            sb.service(GrpcService.builder()
                                  .addService(testService)
                                  .intercept(new ExceptionHandler())
                                  .build());
        }
    };

    @Test
    void shouldNotReturnMalformedResponseHeaders() {
        final ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                                            .usePlaintext()
                                                            .build();
        final TestServiceBlockingStub client = TestServiceGrpc.newBlockingStub(channel);
        final Throwable cause = catchThrowable(() -> client.emptyCall(Empty.getDefaultInstance()));
        final StatusRuntimeException statusException = (StatusRuntimeException) cause;
        assertThat(statusException.getStatus().getCode()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(statusException.getStatus().getDescription()).isEqualTo("oops...");
    }

    static class ExceptionalService extends TestServiceGrpc.TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            throw new IllegalArgumentException("oops...");
        }
    }

    static class ExceptionHandler implements ServerInterceptor {

        @Override
        public <I, O> ServerCall.Listener<I> interceptCall(ServerCall<I, O> serverCall,
                                                           Metadata metadata,
                                                           ServerCallHandler<I, O> serverCallHandler) {
            final ServerCall.Listener<I> listener = serverCallHandler.startCall(serverCall, metadata);
            return new ExceptionHandlingServerCallListener<>(listener, serverCall, metadata);
        }

        private static final class ExceptionHandlingServerCallListener<I, O>
                extends ForwardingServerCallListener.SimpleForwardingServerCallListener<I> {
            private final ServerCall<I, O> serverCall;
            private final Metadata metadata;

            ExceptionHandlingServerCallListener(ServerCall.Listener<I> listener,
                                                ServerCall<I, O> serverCall,
                                                Metadata metadata) {
                super(listener);
                this.serverCall = serverCall;
                this.metadata = metadata;
            }

            @Override
            public void onMessage(I message) {
                try {
                    super.onMessage(message);
                } catch (Exception ex) {
                    handleExceptionWithRequestMetadata(ex, serverCall, metadata);
                }
            }

            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (Exception ex) {
                    handleExceptionWithRequestMetadata(ex, serverCall, metadata);
                }
            }

            @Override
            public void onReady() {
                try {
                    super.onReady();
                } catch (Exception ex) {
                    handleExceptionWithRequestMetadata(ex, serverCall, metadata);
                }
            }

            @Override
            public void onCancel() {
                try {
                    super.onCancel();
                } catch (Exception ex) {
                    handleExceptionWithRequestMetadata(ex, serverCall, metadata);
                }
            }

            @Override
            public void onComplete() {
                try {
                    super.onComplete();
                } catch (Exception ex) {
                    handleExceptionWithRequestMetadata(ex, serverCall, metadata);
                }
            }

            /**
             * Handles an {@link Exception} with a {@link Metadata} received from a request.
             * Armeria server MUST filter out pseudo request headers from the response {@link Metadata} when
             * writing response headers.
             * Otherwise, the upstream gRPC-Java client raises the following exception:
             * {@code Http2Exception$StreamException: Mix of request and response pseudo-headers.}
             */
            private void handleExceptionWithRequestMetadata(Exception exception,
                                                            ServerCall<I, O> serverCall,
                                                            Metadata metadata) {
                if (exception instanceof IllegalArgumentException) {
                    serverCall.close(Status.INVALID_ARGUMENT.withDescription(exception.getMessage()), metadata);
                } else {
                    serverCall.close(Status.UNAVAILABLE, metadata);
                }
            }
        }
    }
}
