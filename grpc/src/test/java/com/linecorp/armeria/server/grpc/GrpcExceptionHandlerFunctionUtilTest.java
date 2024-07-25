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

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleRequest.NestedRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcExceptionHandlerFunctionUtilTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .exceptionHandler((ctx, status, throwable, metadata) -> {
                                      assertThat(throwable).isInstanceOf(RuntimeException.class);
                                      return Status.INTERNAL;
                                  })
                                  .build());
        }
    };

    @CsvSource({ "onError", "throw", "onErrorStatus", "throwStatus" })
    @ParameterizedTest
    void classAndMethodHaveMultipleExceptionHandlers(String exceptionType) {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);

        final SimpleRequest globalRequest =
                SimpleRequest.newBuilder()
                             .setNestedRequest(NestedRequest.newBuilder().setNestedPayload(exceptionType)
                                                            .build())
                             .build();
        assertThatThrownBy(() -> client.unaryCall(globalRequest))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                                        e -> assertThat(e.getStatus()).isEqualTo(Status.INTERNAL));
    }

    private static class TestServiceImpl extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final CompletionException exception = new CompletionException(new RuntimeException());
            switch (request.getNestedRequest().getNestedPayload()) {
                case "onError":
                    responseObserver.onError(exception);
                    break;
                case "throw":
                    throw exception;
                case "onErrorStatus":
                    responseObserver.onError(Status.INTERNAL.withCause(exception).asRuntimeException());
                    break;
                case "throwStatus":
                    throw Status.INTERNAL.withCause(exception).asRuntimeException();
                default:
                    throw new IllegalArgumentException("unknown payload");
            }
        }
    }
}
