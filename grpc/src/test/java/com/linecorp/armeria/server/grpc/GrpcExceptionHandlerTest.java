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

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleRequest.NestedRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;
import testing.grpc.UnitTestBarServiceGrpc.UnitTestBarServiceBlockingStub;
import testing.grpc.UnitTestBarServiceGrpc.UnitTestBarServiceImplBase;
import testing.grpc.UnitTestFooServiceGrpc.UnitTestFooServiceBlockingStub;
import testing.grpc.UnitTestFooServiceGrpc.UnitTestFooServiceImplBase;

class GrpcExceptionHandlerTest {

    private static final BlockingDeque<String> exceptionHandler = new LinkedBlockingDeque<>();

    @BeforeEach
    void setUp() {
        exceptionHandler.clear();
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(5000)
              .decorator(LoggingService.newDecorator())
              .service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .addService(new UnitTestFooServiceImpl())
                                  .addService(new UnitTestBarServiceImpl())
                                  .addService("/foo", new FooTestServiceImpl())
                                  .addService("/bar", new BarTestServiceImpl(),
                                              TestServiceGrpc.getUnaryCallMethod())
                                  .exceptionHandler((ctx, throwable, metadata) -> {
                                      exceptionHandler.add("global");
                                      return Status.INTERNAL;
                                  })
                                  .build());
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithDefaultExceptionHandler = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(5000)
              .service(GrpcService.builder()
                                  .addService(new TestServiceIOException())
                                  .build());
        }
    };

    @Test
    void classAndMethodHaveMultipleExceptionHandlers() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);

        // Global Exception Handler Scenario
        final SimpleRequest globalRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("global")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(globalRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INTERNAL);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("forth");
        assertThat(exceptionHandler.poll()).isEqualTo("third");
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler.poll()).isEqualTo("global");
        assertThat(exceptionHandler).isEmpty();

        // Fist Exception Handler Scenario
        final SimpleRequest firstRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest.newBuilder()
                                               .setNestedPayload("first")
                                               .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(firstRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.UNAUTHENTICATED);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("forth");
        assertThat(exceptionHandler.poll()).isEqualTo("third");
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler).isEmpty();

        // Second Exception Handler Scenario
        final SimpleRequest secondRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest.newBuilder()
                                               .setNestedPayload("second")
                                               .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(secondRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INVALID_ARGUMENT);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("forth");
        assertThat(exceptionHandler.poll()).isEqualTo("third");
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler).isEmpty();

        // Third Exception Handler Scenario
        final SimpleRequest thirdRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest.newBuilder()
                                               .setNestedPayload("third")
                                               .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(thirdRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.NOT_FOUND);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("forth");
        assertThat(exceptionHandler.poll()).isEqualTo("third");
        assertThat(exceptionHandler).isEmpty();

        // Forth Exception Handler Scenario
        final SimpleRequest forthRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest.newBuilder()
                                               .setNestedPayload("forth")
                                               .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(forthRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.UNAVAILABLE);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("forth");
        assertThat(exceptionHandler).isEmpty();

        // No Exception Scenario
        final SimpleRequest fifthRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest.newBuilder()
                                               .setNestedPayload("fifth")
                                               .build())
                .build();
        assertThat(client.unaryCall(fifthRequest)).isNotNull();
        assertThat(exceptionHandler).isEmpty();
    }

    @Test
    void classHasSingleExceptionHandler() {
        final UnitTestFooServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), UnitTestFooServiceBlockingStub.class);

        // Global Exception Handler Scenario
        final SimpleRequest globalRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("global")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.staticUnaryCall(globalRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INTERNAL);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler.poll()).isEqualTo("global");
        assertThat(exceptionHandler).isEmpty();

        // First Exception Handler on class Scenario
        final SimpleRequest firstRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("first")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.staticUnaryCall(firstRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.UNAUTHENTICATED);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler).isEmpty();

        // No Exception Scenario
        final SimpleRequest fifthRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("fifth")
                                          .build())
                .build();
        assertThat(client.staticUnaryCall(fifthRequest)).isNotNull();
        assertThat(exceptionHandler).isEmpty();
    }

    @Test
    void methodHasSingleExceptionHandler() {
        final UnitTestBarServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), UnitTestBarServiceBlockingStub.class);

        // Global Exception Handler Scenario
        final SimpleRequest globalRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("global")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.staticUnaryCall(globalRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INTERNAL);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler.poll()).isEqualTo("global");
        assertThat(exceptionHandler).isEmpty();

        // Global Exception Handler Scenario (call the non-annotated method)
        assertThatThrownBy(() -> client.staticUnaryCall2(globalRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INTERNAL);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("global");
        assertThat(exceptionHandler).isEmpty();

        // First Exception Handler on class Scenario
        final SimpleRequest firstRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("first")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.staticUnaryCall(firstRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.UNAUTHENTICATED);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler).isEmpty();

        // No Exception Scenario
        final SimpleRequest fifthRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("fifth")
                                          .build())
                .build();
        assertThat(client.staticUnaryCall(fifthRequest)).isNotNull();
        assertThat(exceptionHandler).isEmpty();
    }

    @Test
    void prefixService() {
        final TestServiceBlockingStub client = GrpcClients
                .builder(server.httpUri())
                .decorator((delegate, ctx, req) -> {
                    final String path = req.path();
                    final HttpRequest newReq = req.mapHeaders(
                            headers -> headers.toBuilder()
                                              .path(path.replace(
                                                      "armeria.grpc.testing.TestService",
                                                      "foo"))
                                              .build());
                    ctx.updateRequest(newReq);
                    return delegate.execute(ctx, newReq);
                })
                .build(TestServiceBlockingStub.class);

        // Global Exception Handler Scenario
        final SimpleRequest globalRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("global")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(globalRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INTERNAL);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler.poll()).isEqualTo("global");
        assertThat(exceptionHandler).isEmpty();

        // First Exception Handler Scenario
        final SimpleRequest firstRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("first")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(firstRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.UNAUTHENTICATED);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler).isEmpty();

        // Second Exception Handler Scenario
        final SimpleRequest secondRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("second")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(secondRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INVALID_ARGUMENT);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler).isEmpty();

        // No Exception Scenario
        final SimpleRequest fifthRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("fifth")
                                          .build())
                .build();
        assertThat(client.unaryCall(fifthRequest)).isNotNull();
        assertThat(exceptionHandler).isEmpty();
    }

    @Test
    void solelyAddedMethod() {
        final TestServiceBlockingStub client = GrpcClients
                .builder(server.httpUri())
                .decorator((delegate, ctx, req) -> {
                    final HttpRequest newReq = req.mapHeaders(
                            headers -> headers.toBuilder().path("/bar").build());
                    ctx.updateRequest(newReq);
                    return delegate.execute(ctx, newReq);
                })
                .build(TestServiceBlockingStub.class);

        // Global Exception Handler Scenario
        final SimpleRequest globalRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("global")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(globalRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INTERNAL);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler.poll()).isEqualTo("global");
        assertThat(exceptionHandler).isEmpty();

        // First Exception Handler Scenario
        final SimpleRequest firstRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("first")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(firstRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.UNAUTHENTICATED);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler).isEmpty();

        // Second Exception Handler Scenario
        final SimpleRequest secondRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("second")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(secondRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INVALID_ARGUMENT);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler).isEmpty();

        // No Exception Scenario
        final SimpleRequest fifthRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("fifth")
                                          .build())
                .build();
        assertThat(client.unaryCall(fifthRequest)).isNotNull();
    }

    @Test
    void defaultGrpcExceptionHandlerConvertIOExceptionToUnavailable() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(serverWithDefaultExceptionHandler.httpUri(),
                                      TestServiceBlockingStub.class);

        final SimpleRequest globalRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("global")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.unaryCall(globalRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
                });
    }

    private static class FirstGrpcExceptionHandler implements GrpcExceptionHandlerFunction {

        @Override
        public @Nullable Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
            exceptionHandler.add("first");
            if (Objects.equals(cause.getMessage(), "first")) {
                return Status.UNAUTHENTICATED;
            }
            return null;
        }
    }

    private static class SecondGrpcExceptionHandler implements GrpcExceptionHandlerFunction {

        @Override
        public @Nullable Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
            exceptionHandler.add("second");
            if (Objects.equals(cause.getMessage(), "second")) {
                return Status.INVALID_ARGUMENT;
            }
            return null;
        }
    }

    private static class ThirdGrpcExceptionHandler implements GrpcExceptionHandlerFunction {

        @Override
        public @Nullable Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
            exceptionHandler.add("third");
            if (Objects.equals(cause.getMessage(), "third")) {
                return Status.NOT_FOUND;
            }
            return null;
        }
    }

    private static class ForthGrpcExceptionHandler implements GrpcExceptionHandlerFunction {

        @Override
        public @Nullable Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
            exceptionHandler.add("forth");
            if (Objects.equals(cause.getMessage(), "forth")) {
                return Status.UNAVAILABLE;
            }
            return null;
        }
    }

    private static void checkArgument(SimpleRequest request) {
        final String message = request.getNestedRequest().getNestedPayload();
        switch (message) {
            case "first":
            case "second":
            case "third":
            case "forth":
            case "global":
                throw new RuntimeException(message);
        }
    }

    @GrpcExceptionHandler(SecondGrpcExceptionHandler.class)
    @GrpcExceptionHandler(FirstGrpcExceptionHandler.class)
    private static class TestServiceImpl extends TestServiceImplBase {

        @GrpcExceptionHandler(ForthGrpcExceptionHandler.class)
        @GrpcExceptionHandler(ThirdGrpcExceptionHandler.class)
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkArgument(request);
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @GrpcExceptionHandler(FirstGrpcExceptionHandler.class)
    private static class UnitTestFooServiceImpl extends UnitTestFooServiceImplBase {

        @Override
        public void staticUnaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkArgument(request);
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    private static class UnitTestBarServiceImpl extends UnitTestBarServiceImplBase {

        @GrpcExceptionHandler(FirstGrpcExceptionHandler.class)
        @Override
        public void staticUnaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkArgument(request);
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void staticUnaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkArgument(request);
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    @GrpcExceptionHandler(FirstGrpcExceptionHandler.class)
    private static class FooTestServiceImpl extends TestServiceImplBase {

        @GrpcExceptionHandler(SecondGrpcExceptionHandler.class)
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkArgument(request);
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("foo user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    @GrpcExceptionHandler(FirstGrpcExceptionHandler.class)
    private static class BarTestServiceImpl extends TestServiceImplBase {

        @GrpcExceptionHandler(SecondGrpcExceptionHandler.class)
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkArgument(request);
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("bar user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    // TestServiceIOException has DefaultGRPCExceptionHandlerFunction as fallback exception handler
    private static class TestServiceIOException extends TestServiceImpl {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(new IOException());
        }
    }
}
