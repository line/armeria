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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.auth.Authorizer;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class AsyncServerInterceptorTest {
    private static final AtomicInteger exceptionCounter = new AtomicInteger(0);
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final GrpcExceptionHandlerFunction exceptionHandler = (ctx, throwable, metadata) -> {
                exceptionCounter.getAndIncrement();
                if (throwable instanceof AnticipatedException &&
                    "Invalid access".equals(throwable.getMessage())) {
                    return Status.UNAUTHENTICATED;
                }
                // Fallback to the default.
                return null;
            };
            final AuthInterceptor authInterceptor = new AuthInterceptor();
            sb.serviceUnder("/non-blocking", GrpcService.builder()
                                                        .exceptionHandler(exceptionHandler)
                                                        .intercept(authInterceptor)
                                                        .addService(new TestService())
                                                        .build());
            sb.serviceUnder("/blocking", GrpcService.builder()
                                                    .addService(new TestService())
                                                    .exceptionHandler(exceptionHandler)
                                                    .intercept(authInterceptor)
                                                    .useBlockingTaskExecutor(true)
                                                    .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        exceptionCounter.set(0);
    }

    @ValueSource(strings = { "/non-blocking", "/blocking" })
    @ParameterizedTest
    void authorizedRequest(String path) {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .pathPrefix(path)
                                                          .auth(AuthToken.ofOAuth2("token-1234"))
                                                          .build(TestServiceBlockingStub.class);
        final SimpleResponse response = client.unaryCall(SimpleRequest.newBuilder()
                                                                      .setFillUsername(true)
                                                                      .build());
        assertThat(response.getUsername()).isEqualTo("Armeria");
        assertThat(exceptionCounter.get()).isEqualTo(0);
    }

    @ValueSource(strings = { "/non-blocking", "/blocking" })
    @ParameterizedTest
    void unauthorizedRequest(String path) {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .pathPrefix(path)
                                                          .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> {
            client.unaryCall(SimpleRequest.newBuilder()
                                          .setFillUsername(true)
                                          .build());
        }).isInstanceOf(StatusRuntimeException.class)
          .satisfies(cause -> {
              assertThat(((StatusRuntimeException) cause).getStatus())
                      .isEqualTo(Status.UNAUTHENTICATED);
          });
    }

    private static final class AuthInterceptor implements AsyncServerInterceptor {

        private final Authorizer<Metadata> authorizer = (ctx, metadata) -> {
            final CompletableFuture<Boolean> future = new CompletableFuture<>();
            // Simulate an asynchronous call.
            ctx.eventLoop().schedule(() -> {
                if (ctx.request().headers().contains("Authorization", "Bearer token-1234")) {
                    future.complete(true);
                } else {
                    future.complete(false);
                }
            }, 100, TimeUnit.MILLISECONDS);
            return future;
        };

        @Override
        public <I, O> CompletableFuture<Listener<I>> asyncInterceptCall(ServerCall<I, O> call, Metadata headers,
                                                                        ServerCallHandler<I, O> next) {
            // An interceptor should be called in a context-aware thread.
            return authorizer.authorize(ServiceRequestContext.current(), headers)
                             .thenApply(result -> {
                                 if (result) {
                                     return next.startCall(call, headers);
                                 }
                                 throw new AnticipatedException("Invalid access");
                             }).toCompletableFuture();
        }
    }

    private static final class TestService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            if (request.getFillUsername()) {
                responseObserver.onNext(SimpleResponse.newBuilder().setUsername("Armeria").build());
            } else {
                responseObserver.onNext(SimpleResponse.getDefaultInstance());
            }
            responseObserver.onCompleted();
        }
    }
}
