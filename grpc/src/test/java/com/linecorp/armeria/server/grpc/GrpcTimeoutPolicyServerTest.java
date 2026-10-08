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
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
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
                                  .timeoutPolicy(GrpcTimeoutPolicy.useGrpcTimeoutHeader(
                                          MAX, Duration.ofSeconds(-5)))
                                  .addService(new TimeoutReportingService())
                                  .build());
        }
    };

    @RegisterExtension
    static ServerExtension keepServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(SERVICE_TIMEOUT_MILLIS);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy((ctx, method, clientTimeout) -> Duration.ofSeconds(-1))
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
        assertTimeoutIsAbout(requestTimeoutMillis(client.withDeadlineAfter(1, TimeUnit.HOURS)),
                             MAX.toMillis());
    }

    @Test
    void shortClientTimeoutIsKept() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        assertTimeoutIsAbout(requestTimeoutMillis(client.withDeadlineAfter(500, TimeUnit.MILLISECONDS)),
                             500);
    }

    @Test
    void missingClientTimeoutIsCapped() {
        // A client that does not send a 'grpc-timeout' header must not get an infinite timeout, either.
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .responseTimeoutMillis(0)
                                                          .build(TestServiceBlockingStub.class);
        assertTimeoutIsAbout(requestTimeoutMillis(client), MAX.toMillis());
    }

    @Test
    void negativeOffsetShortensTheTimeout() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(offsetServer.httpUri(), TestServiceBlockingStub.class);
        // 8s is under the max, so only the offset applies.
        assertTimeoutIsAbout(requestTimeoutMillis(client.withDeadlineAfter(8, TimeUnit.SECONDS)), 3_000);
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
    void zeroClientTimeoutMeansNoTimeout() {
        // A client asking for no timeout must be capped like one that omits the header, rather than failing.
        assertTimeoutIsAbout(requestTimeoutMillis(clientSendingTimeout("0S")), MAX.toMillis());
    }

    @Test
    void negativeClientTimeoutIsRejected() {
        // A negative 'grpc-timeout' is malformed, and must not be read as a request to keep the timeout.
        assertThatThrownBy(() -> requestTimeoutMillis(clientSendingTimeout("-1S")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.INVALID_ARGUMENT);
                });
    }

    /**
     * Returns a client that sends the specified {@code timeoutHeader} verbatim, which a gRPC stub would not
     * produce on its own.
     */
    private static TestServiceBlockingStub clientSendingTimeout(String timeoutHeader) {
        return GrpcClients.builder(server.httpUri())
                          .responseTimeoutMillis(0)
                          .setHeader(GrpcHeaderNames.GRPC_TIMEOUT, timeoutHeader)
                          .build(TestServiceBlockingStub.class);
    }

    @Test
    void negativeTimeoutKeepsTheCurrentOne() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(keepServer.httpUri(), TestServiceBlockingStub.class);
        assertTimeoutIsAbout(requestTimeoutMillis(client.withDeadlineAfter(1, TimeUnit.HOURS)),
                             SERVICE_TIMEOUT_MILLIS);
    }

    @Test
    void timeoutCanBeDecidedPerMethod() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(perMethodServer.httpUri(), TestServiceBlockingStub.class)
                           .withDeadlineAfter(1, TimeUnit.HOURS);
        final SimpleRequest req = SimpleRequest.getDefaultInstance();
        assertTimeoutIsAbout(Long.parseLong(client.unaryCall(req).getUsername()), 1000);
        assertTimeoutIsAbout(Long.parseLong(client.unaryCall2(req).getUsername()), MAX.toMillis());
    }

    private static long requestTimeoutMillis(TestServiceBlockingStub client) {
        return Long.parseLong(client.unaryCall(SimpleRequest.getDefaultInstance()).getUsername());
    }

    /**
     * Asserts that the specified {@code actualMillis} is the {@code expectedMillis} the policy decided, give or
     * take a second, because a timeout does not survive a round trip to the millisecond. A client subtracts
     * what its deadline already spent before writing the {@code grpc-timeout} header, and a server sets the
     * timeout from the moment the service is reached while
     * {@link ServiceRequestContext#requestTimeoutMillis()} reports it relative to the start of the request.
     */
    private static void assertTimeoutIsAbout(long actualMillis, long expectedMillis) {
        assertThat(actualMillis).isBetween(expectedMillis - 1000, expectedMillis + 1000);
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
