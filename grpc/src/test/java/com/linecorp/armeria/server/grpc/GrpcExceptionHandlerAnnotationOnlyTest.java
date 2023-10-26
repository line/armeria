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

import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
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
import testing.grpc.UnitTestFooServiceGrpc.UnitTestFooServiceBlockingStub;
import testing.grpc.UnitTestFooServiceGrpc.UnitTestFooServiceImplBase;

class GrpcExceptionHandlerAnnotationOnlyTest {

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
                                  .addService(new UnitTestFooServiceImpl())
                                  .build());
        }
    };

    @Test
    void exceptionHandler() {
        final UnitTestFooServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), UnitTestFooServiceBlockingStub.class);

        // Global Exception Handler Doesn't Exist Scenario
        final SimpleRequest globalRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("global")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.staticUnaryCall(globalRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.UNKNOWN.getCode());
                });
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler.poll()).isEqualTo("first");
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
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler.poll()).isEqualTo("first");
        assertThat(exceptionHandler).isEmpty();

        // Second Exception Handler on method Scenario
        final SimpleRequest secondRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("second")
                                          .build())
                .build();
        assertThatThrownBy(() -> client.staticUnaryCall(secondRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(Status.INVALID_ARGUMENT);
                });
        assertThat(exceptionHandler.poll()).isEqualTo("second");
        assertThat(exceptionHandler).isEmpty();

        // No Exception Scenario
        final SimpleRequest thirdRequest = SimpleRequest
                .newBuilder()
                .setNestedRequest(NestedRequest
                                          .newBuilder()
                                          .setNestedPayload("third")
                                          .build())
                .build();
        assertThat(client.staticUnaryCall(thirdRequest)).isNotNull();
        assertThat(exceptionHandler).isEmpty();
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

    private static class SecondGrpcExceptionHandler  implements GrpcExceptionHandlerFunction {

        @Override
        public @Nullable Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
            exceptionHandler.add("second");
            if (Objects.equals(cause.getMessage(), "second")) {
                return Status.INVALID_ARGUMENT;
            }
            return null;
        }
    }

    @GrpcExceptionHandler(FirstGrpcExceptionHandler.class)
    private static class UnitTestFooServiceImpl extends UnitTestFooServiceImplBase {

        @GrpcExceptionHandler(SecondGrpcExceptionHandler.class)
        @Override
        public void staticUnaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            checkArgument(request);
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        private static void checkArgument(SimpleRequest request) {
            final String message = request.getNestedRequest().getNestedPayload();
            switch (message) {
                case "first":
                case "second":
                case "global":
                    throw new RuntimeException(message);
            }
        }
    }
}
