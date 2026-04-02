/*
 * Copyright 2026 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.grpc.AsyncGrpcExceptionHandlerFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class AsyncGrpcExceptionHandlerTest {

    private static final Executor ASYNC_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final Metadata.Key<String> ERROR_MESSAGE_KEY =
            Metadata.Key.of("error-message", Metadata.ASCII_STRING_MARSHALLER);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000)
              .service(GrpcService.builder()
                                  .addService(new ErrorThrowingService())
                                  .asyncExceptionHandler(
                                          (ctx, status, cause, metadata) -> {
                                      // Simulate an async operation (e.g., i18n lookup).
                                      return CompletableFuture.supplyAsync(() -> {
                                          metadata.put(ERROR_MESSAGE_KEY,
                                                       "translated: " + cause.getMessage());
                                          return Status.INTERNAL
                                                  .withDescription("async-handled")
                                                  .withCause(cause);
                                      }, ASYNC_EXECUTOR);
                                  })
                                  .build());
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithNullFallback = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000)
              .service(GrpcService.builder()
                                  .addService(new ErrorThrowingService())
                                  .asyncExceptionHandler(
                                          (ctx, status, cause, metadata) -> {
                                      // Return null to fall back to default handler.
                                      return CompletableFuture.completedFuture(null);
                                  })
                                  .build());
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithOrElse = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final AsyncGrpcExceptionHandlerFunction first =
                    (ctx, status, cause, metadata) -> {
                        if (cause instanceof IllegalArgumentException) {
                            return CompletableFuture.completedFuture(
                                    Status.INVALID_ARGUMENT.withDescription("first-handler")
                                                           .withCause(cause));
                        }
                        return CompletableFuture.completedFuture(null);
                    };
            final AsyncGrpcExceptionHandlerFunction second =
                    (ctx, status, cause, metadata) ->
                            CompletableFuture.completedFuture(
                                    Status.INTERNAL.withDescription("second-handler")
                                                   .withCause(cause));

            sb.requestTimeoutMillis(5000)
              .service(GrpcService.builder()
                                  .addService(new ErrorThrowingService())
                                  .asyncExceptionHandler(first.orElse(second))
                                  .build());
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithFailingHandler = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000)
              .service(GrpcService.builder()
                                  .addService(new ErrorThrowingService())
                                  .asyncExceptionHandler(
                                          (ctx, status, cause, metadata) -> {
                                      final CompletableFuture<Status> future = new CompletableFuture<>();
                                      future.completeExceptionally(
                                              new RuntimeException("handler failed"));
                                      return future;
                                  })
                                  .build());
        }
    };

    @Test
    void asyncHandlerReturnsCustomStatus() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        assertThatThrownBy(() -> client.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                    assertThat(e.getStatus().getDescription()).isEqualTo("async-handled");
                });
    }

    @Test
    void asyncHandlerMutatesMetadata() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        assertThatThrownBy(() -> client.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    final Metadata trailers = e.getTrailers();
                    assertThat(trailers).isNotNull();
                    assertThat(trailers.get(ERROR_MESSAGE_KEY))
                            .isEqualTo("translated: test error");
                });
    }

    @Test
    void asyncHandlerReturnsNullFallsBackToDefault() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(serverWithNullFallback.httpUri(),
                                      TestServiceBlockingStub.class);
        assertThatThrownBy(() -> client.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    // Default handler maps unknown exceptions to UNKNOWN.
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
                });
    }

    @Test
    void orElseChainingFirstHandlerMatches() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(serverWithOrElse.httpUri(), TestServiceBlockingStub.class);
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setFillUsername(true)
                                                   .build();
        assertThatThrownBy(() -> client.unaryCall(request))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription()).isEqualTo("first-handler");
                });
    }

    @Test
    void orElseChainingSecondHandlerHandles() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(serverWithOrElse.httpUri(), TestServiceBlockingStub.class);
        assertThatThrownBy(() -> client.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                    assertThat(e.getStatus().getDescription()).isEqualTo("second-handler");
                });
    }

    @Test
    void failingAsyncHandlerFallsBackToOriginalStatus() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(serverWithFailingHandler.httpUri(),
                                      TestServiceBlockingStub.class);
        assertThatThrownBy(() -> client.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    // When the async handler fails, the original status should be preserved.
                    assertThat(e.getStatus().getCode()).isNotNull();
                });
    }

    private static class ErrorThrowingService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            if (request.getFillUsername()) {
                throw new IllegalArgumentException("invalid argument error");
            }
            throw new RuntimeException("test error");
        }
    }
}
