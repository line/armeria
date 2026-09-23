/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcTimeoutPolicyServerTest {

    private static final long SERVICE_TIMEOUT_MILLIS = 2000;
    private static final Duration MAX = Duration.ofSeconds(10);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(SERVICE_TIMEOUT_MILLIS);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy(GrpcTimeoutPolicy.useGrpcTimeoutHeader(MAX))
                                  .addService(new TimeoutReportingService())
                                  .build());
        }
    };

    @RegisterExtension
    static ServerExtension offsetServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(SERVICE_TIMEOUT_MILLIS);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy(GrpcTimeoutPolicy.useGrpcTimeoutHeader()
                                                                  .withOffset(Duration.ofSeconds(-5)))
                                  .addService(new TimeoutReportingService())
                                  .build());
        }
    };

    @RegisterExtension
    static ServerExtension perMethodServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(SERVICE_TIMEOUT_MILLIS);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy((ctx, method, clientTimeout) -> {
                                      // Give 'UnaryCall' a tighter bound than the rest.
                                      if ("UnaryCall".equals(
                                              method.getMethodDescriptor().getBareMethodName())) {
                                          return Duration.ofSeconds(1);
                                      }
                                      return GrpcTimeoutPolicy.useGrpcTimeoutHeader(MAX)
                                                              .apply(ctx, method, clientTimeout);
                                  })
                                  .addService(new TimeoutReportingService())
                                  .build());
        }
    };

    @Test
    void longClientTimeoutIsCapped() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        assertThat(requestTimeoutMillis(client.withDeadlineAfter(1, TimeUnit.HOURS)))
                .isEqualTo(MAX.toMillis());
    }

    @Test
    void shortClientTimeoutIsKept() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        assertThat(requestTimeoutMillis(client.withDeadlineAfter(500, TimeUnit.MILLISECONDS)))
                .isLessThanOrEqualTo(500);
    }

    @Test
    void missingClientTimeoutIsCapped() {
        // A client that does not send a 'grpc-timeout' header must not get an infinite timeout, either.
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .responseTimeoutMillis(0)
                                                          .build(TestServiceBlockingStub.class);
        assertThat(requestTimeoutMillis(client)).isEqualTo(MAX.toMillis());
    }

    @Test
    void negativeOffsetShortensTheTimeout() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(offsetServer.httpUri(), TestServiceBlockingStub.class);
        assertThat(requestTimeoutMillis(client.withDeadlineAfter(30, TimeUnit.SECONDS)))
                .isLessThanOrEqualTo(25_000)
                .isGreaterThan(24_000);
    }

    @Test
    void exhaustedDeadlineFailsImmediately() {
        // 3s minus the 5s offset leaves nothing, so the request must fail instead of becoming infinite.
        final TestServiceBlockingStub client =
                GrpcClients.newClient(offsetServer.httpUri(), TestServiceBlockingStub.class);
        assertThatThrownBy(() -> requestTimeoutMillis(client.withDeadlineAfter(3, TimeUnit.SECONDS)))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
                });
    }

    @Test
    void timeoutCanBeDecidedPerMethod() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(perMethodServer.httpUri(), TestServiceBlockingStub.class)
                           .withDeadlineAfter(1, TimeUnit.HOURS);
        final SimpleRequest req = SimpleRequest.getDefaultInstance();
        assertThat(Long.parseLong(client.unaryCall(req).getUsername())).isEqualTo(1000);
        assertThat(Long.parseLong(client.unaryCall2(req).getUsername())).isEqualTo(MAX.toMillis());
    }

    private static long requestTimeoutMillis(TestServiceBlockingStub client) {
        return Long.parseLong(client.unaryCall(SimpleRequest.getDefaultInstance()).getUsername());
    }

    private static class TimeoutReportingService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            reportTimeout(responseObserver);
        }

        @Override
        public void unaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            reportTimeout(responseObserver);
        }

        private static void reportTimeout(StreamObserver<SimpleResponse> responseObserver) {
            final long timeoutMillis = ServiceRequestContext.current().requestTimeoutMillis();
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername(String.valueOf(timeoutMillis))
                                                  .build());
            responseObserver.onCompleted();
        }
    }
}
