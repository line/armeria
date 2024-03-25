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

package com.linecorp.armeria.common.grpc;

import static com.linecorp.armeria.internal.common.grpc.MetadataUtil.GRPC_STATUS_DETAILS_BIN_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Code;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.grpc.testing.Error.AuthError;
import com.linecorp.armeria.grpc.testing.Error.InternalError;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GoogleGrpcExceptionHandlerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .intercept(new AuthInterceptor())
                                  .exceptionHandler(new ExceptionHandler())
                                  .addService(new TestService())
                                  .build())
              .decorator(LoggingService.newDecorator());
        }
    };

    @Test
    void applyInternalError() {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .auth(AuthToken.ofOAuth2("token-1234"))
                                                          .build(TestServiceBlockingStub.class);
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setFillUsername(true)
                                                   .build();
        assertThatThrownBy(() -> client.unaryCall(request))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.INTERNAL.getCode());
                    final com.google.rpc.Status status = e.getTrailers().get(GRPC_STATUS_DETAILS_BIN_KEY);
                    assertThat(status).isNotNull();
                    assertThat(status.getCode()).isEqualTo(Code.INTERNAL.getNumber());
                    assertThat(status.getDetailsCount()).isEqualTo(1);
                    final InternalError internalError;
                    try {
                        internalError = status.getDetails(0).unpack(InternalError.class);
                    } catch (InvalidProtocolBufferException ex) {
                        throw new RuntimeException(ex);
                    }
                    assertThat(internalError.getCode()).isEqualTo(123);
                    assertThat(internalError.getMessage()).isEqualTo("Unexpected error");
                });
    }

    @Test
    void applyAuthError() {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .auth(AuthToken.ofOAuth2("token-12345"))
                                                          .build(TestServiceBlockingStub.class);
        final SimpleRequest request = SimpleRequest.newBuilder()
                                                   .setFillUsername(true)
                                                   .build();
        assertThatThrownBy(() -> client.unaryCall(request))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
                    final com.google.rpc.Status status = e.getTrailers().get(GRPC_STATUS_DETAILS_BIN_KEY);
                    assertThat(status).isNotNull();
                    assertThat(status.getCode()).isEqualTo(Code.UNAUTHENTICATED.getNumber());
                    assertThat(status.getDetailsCount()).isEqualTo(1);
                    final AuthError authError;
                    try {
                        authError = status.getDetails(0).unpack(AuthError.class);
                    } catch (InvalidProtocolBufferException ex) {
                        throw new RuntimeException(ex);
                    }
                    assertThat(authError.getCode()).isEqualTo(334);
                    assertThat(authError.getMessage()).isEqualTo("Invalid token");
                });
    }

    @Test
    void earlyReturnStatusRuntimeException() {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .auth(AuthToken.ofOAuth2("token-1234"))
                                                          .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> client.emptyCall(Empty.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.INTERNAL.getCode());
                    final com.google.rpc.Status status = e.getTrailers().get(GRPC_STATUS_DETAILS_BIN_KEY);
                    assertThat(status).isNotNull();
                    assertThat(status.getCode()).isEqualTo(Code.INTERNAL.getNumber());
                    assertThat(status.getMessage()).isEqualTo("Database failure");
                    assertThat(status.getDetailsCount()).isEqualTo(1);
                    final InternalError internalError;
                    try {
                        internalError = status.getDetails(0).unpack(InternalError.class);
                    } catch (InvalidProtocolBufferException ex) {
                        throw new RuntimeException(ex);
                    }
                    assertThat(internalError.getCode()).isEqualTo(321);
                    assertThat(internalError.getMessage()).isEqualTo("Primary DB failure");
                });
    }

    private static final class TestService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            if (request.getFillUsername()) {
                throw new InternalServerException("Unexpected error", 123);
            }
            responseObserver.onNext(SimpleResponse.newBuilder().setUsername("Armeria").build());
            responseObserver.onCompleted();
        }

        @Override
        public void emptyCall(Empty empty, StreamObserver<Empty> responseObserver) {
            final InternalError internalError = InternalError.newBuilder()
                                                             .setCode(321)
                                                             .setMessage("Primary DB failure")
                                                             .build();
            final com.google.rpc.Status status = com.google.rpc.Status.newBuilder()
                                                                      .setCode(Code.INTERNAL.getNumber())
                                                                      .setMessage("Database failure")
                                                                      .addDetails(Any.pack(internalError))
                                                                      .build();
            throw StatusProto.toStatusRuntimeException(status);
        }
    }

    private static final class ExceptionHandler implements GoogleGrpcExceptionHandlerFunction {

        @Override
        public com.google.rpc.Status applyStatusProto(RequestContext ctx, Throwable throwable,
                                                      Metadata metadata) {
            if (throwable instanceof AuthenticationException) {
                final AuthenticationException authenticationException = (AuthenticationException) throwable;
                final AuthError authError = AuthError.newBuilder()
                                                     .setCode(authenticationException.getCode())
                                                     .setMessage(authenticationException.getMessage())
                                                     .build();
                return com.google.rpc.Status.newBuilder()
                                            .setCode(Code.UNAUTHENTICATED.getNumber())
                                            .addDetails(Any.pack(authError))
                                            .build();
            }
            if (throwable instanceof InternalServerException) {
                final InternalServerException internalServerException = (InternalServerException) throwable;
                final InternalError internalError = InternalError
                        .newBuilder()
                        .setCode(internalServerException.getCode())
                        .setMessage(internalServerException.getMessage())
                        .build();
                return com.google.rpc.Status.newBuilder()
                                            .setCode(Code.INTERNAL.getNumber())
                                            .addDetails(Any.pack(internalError))
                                            .build();
            }
            return null;
        }
    }

    private static final class AuthInterceptor implements ServerInterceptor {

        @Override
        public <I, O> Listener<I> interceptCall(ServerCall<I, O> call, Metadata headers,
                                                ServerCallHandler<I, O> next) {
            final ServiceRequestContext ctx = ServiceRequestContext.current();
            if (!ctx.request().headers().contains("Authorization", "Bearer token-1234")) {
                throw new AuthenticationException("Invalid token", 334);
            }
            return next.startCall(call, headers);
        }
    }

    private static final class InternalServerException extends RuntimeException {

        private final int code;

        InternalServerException(String message, int code) {
            super(message);
            this.code = code;
        }

        int getCode() {
            return code;
        }
    }

    private static class AuthenticationException extends RuntimeException {

        private final int code;

        AuthenticationException(String message, int code) {
            super(message);
            this.code = code;
        }

        int getCode() {
            return code;
        }
    }
}
