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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
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
    private static final AtomicLong delayedRequestTimeoutMillis = new AtomicLong(-1);
    private static final AtomicReference<CompletableFuture<Throwable>> delayedRequestCancellation =
            new AtomicReference<>();

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

    @RegisterExtension
    static ServerExtension delayedServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(5000);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy(GrpcTimeoutPolicy.useGrpcTimeoutHeader(MAX))
                                  .addService(new DelayedResponseService())
                                  .build());
            sb.decorator((delegate, ctx, req) -> {
                final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                ctx.eventLoop().schedule(() -> {
                    try {
                        responseFuture.complete(delegate.serve(ctx, req));
                    } catch (Throwable cause) {
                        responseFuture.completeExceptionally(cause);
                    }
                }, 400, TimeUnit.MILLISECONDS);
                return HttpResponse.of(responseFuture);
            });
        }
    };

    @RegisterExtension
    static ServerExtension slowInterceptorServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(0);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy(GrpcTimeoutPolicy.useGrpcTimeoutHeader(MAX))
                                  .addService(new TimeoutReportingService())
                                  .useBlockingTaskExecutor(true)
                                  .intercept(slowInterceptor())
                                  .build());
        }
    };

    /**
     * Times out inside a blocking interceptor like {@link #slowInterceptorServer}, but with the service
     * timeout rather than a client deadline.
     */
    @RegisterExtension
    static ServerExtension slowInterceptorWithoutDeadlineServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(300);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy(GrpcTimeoutPolicy.useServiceTimeout())
                                  .addService(new TimeoutReportingService())
                                  .useBlockingTaskExecutor(true)
                                  .intercept(slowInterceptor())
                                  .build());
        }
    };

    /** The same window, reached by an unframed request. */
    @RegisterExtension
    static ServerExtension slowInterceptorUnframedServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(300);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy(GrpcTimeoutPolicy.useServiceTimeout())
                                  .addService(new TimeoutReportingService())
                                  .enableUnframedRequests(true)
                                  .useBlockingTaskExecutor(true)
                                  .intercept(slowInterceptor())
                                  .build());
        }
    };

    /** Shortens the client deadline by 500ms and burns 400ms before the service sees the request. */
    @RegisterExtension
    static ServerExtension offsetDelayedServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(0);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy(GrpcTimeoutPolicy.useGrpcTimeoutHeader(
                                          MAX, Duration.ofMillis(-500)))
                                  .addService(new TimeoutReportingService())
                                  .build());
            sb.decorator((delegate, ctx, req) -> {
                final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
                ctx.eventLoop().schedule(() -> {
                    try {
                        responseFuture.complete(delegate.serve(ctx, req));
                    } catch (Throwable cause) {
                        responseFuture.completeExceptionally(cause);
                    }
                }, 400, TimeUnit.MILLISECONDS);
                return HttpResponse.of(responseFuture);
            });
        }
    };

    /** A bounded policy short enough to expire inside the interceptor even for a client asking for none. */
    @RegisterExtension
    static ServerExtension slowInterceptorBoundedServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(0);
            sb.service(GrpcService.builder()
                                  .timeoutPolicy(GrpcTimeoutPolicy.useGrpcTimeoutHeader(
                                          Duration.ofMillis(300)))
                                  .addService(new TimeoutReportingService())
                                  .useBlockingTaskExecutor(true)
                                  .intercept(slowInterceptor())
                                  .build());
        }
    };

    /** Fails with something other than a timeout, which must take the usual gRPC error path. */
    @RegisterExtension
    static ServerExtension throwingServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .timeoutPolicy(GrpcTimeoutPolicy.useGrpcTimeoutHeader(MAX))
                                  .addService(new TestServiceImplBase() {
                                      @Override
                                      public void unaryCall(SimpleRequest request,
                                                            StreamObserver<SimpleResponse> observer) {
                                          throw new IllegalStateException("not a timeout");
                                      }
                                  })
                                  .build());
        }
    };

    /** Holds up the call on the blocking executor so that a deadline expires before gRPC starts it. */
    private static ServerInterceptor slowInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <I, O> ServerCall.Listener<I> interceptCall(
                    ServerCall<I, O> call, Metadata headers, ServerCallHandler<I, O> next) {
                Uninterruptibles.sleepUninterruptibly(Duration.ofSeconds(1));
                return next.startCall(call, headers);
            }
        };
    }

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
    void clientTimeoutStartsWhenRequestArrives() throws Exception {
        delayedRequestTimeoutMillis.set(-1);
        delayedRequestCancellation.set(null);
        final TestServiceBlockingStub client = GrpcClients.builder(delayedServer.httpUri())
                                                          .responseTimeoutMillis(0)
                                                          .setHeader(GrpcHeaderNames.GRPC_TIMEOUT, "700m")
                                                          .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> requestTimeoutMillis(client))
                .isInstanceOf(StatusRuntimeException.class);
        assertThat(delayedRequestTimeoutMillis.get()).isEqualTo(700);
        final CompletableFuture<Throwable> cancellation = delayedRequestCancellation.get();
        assertThat(cancellation).isNotNull();
        assertThat(cancellation.get(1, TimeUnit.SECONDS)).isInstanceOf(RequestTimeoutException.class);
    }

    @Test
    void deadlineAlreadyExhaustedFailsWithDeadlineExceeded() {
        // The pipeline takes 400ms before the service, so a 300ms deadline is already past when the timeout
        // is applied. That must fail the same way a policy returning Duration.ZERO does, rather than being
        // cancelled before a response has started - which a client sees as UNAVAILABLE.
        final TestServiceBlockingStub client = GrpcClients.builder(delayedServer.httpUri())
                                                          .responseTimeoutMillis(0)
                                                          .setHeader(GrpcHeaderNames.GRPC_TIMEOUT, "300m")
                                                          .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> requestTimeoutMillis(client))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
                });
    }

    @Test
    void deadlineExpiresWhileBlockingInterceptorIsRunning() {
        final TestServiceBlockingStub client =
                GrpcClients.builder(slowInterceptorServer.httpUri())
                           .responseTimeoutMillis(0)
                           .setHeader(GrpcHeaderNames.GRPC_TIMEOUT, "300m")
                           .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> requestTimeoutMillis(client))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
                });
    }

    @Test
    void deadlineExpiresWhileBlockingInterceptorIsRunningWithoutClientDeadline() {
        // No 'grpc-timeout', so there is no client deadline to exceed and gRPC reports CANCELLED.
        final TestServiceBlockingStub client =
                GrpcClients.builder(slowInterceptorWithoutDeadlineServer.httpUri())
                           .responseTimeoutMillis(0)
                           .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> requestTimeoutMillis(client))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.CANCELLED);
                });
    }

    @Test
    void unframedRequestKeepsRenderingAPlainHttpResponse() {
        // An unframed request must not be answered with gRPC trailers, so the timeout is left to the
        // default error handler.
        final AggregatedHttpResponse res =
                WebClient.builder(slowInterceptorUnframedServer.httpUri())
                         .responseTimeoutMillis(0)
                         .build()
                         .blocking()
                         .execute(RequestHeaders.builder(HttpMethod.POST,
                                                         "/armeria.grpc.testing.TestService/UnaryCall")
                                                .contentType(MediaType.JSON_UTF_8)
                                                .build(),
                                  "{}");
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        // gRPC trailers would carry a 'grpc-status', which an unframed client cannot read.
        assertThat(res.headers().get(GrpcHeaderNames.GRPC_STATUS)).isNull();
    }

    @Test
    void zeroClientTimeoutStillGetsTheBoundedDeadline() {
        // 'grpc-timeout: 0S' asks for no deadline, so the policy's 'max' becomes the deadline and
        // exceeding it is a gRPC deadline, not a server-side cancellation.
        final TestServiceBlockingStub client =
                GrpcClients.builder(slowInterceptorBoundedServer.httpUri())
                           .responseTimeoutMillis(0)
                           .setHeader(GrpcHeaderNames.GRPC_TIMEOUT, "0S")
                           .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> requestTimeoutMillis(client))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
                });
    }

    @Test
    void negativeOffsetReportsDeadlineExceededBeforeTheClientDeadline() {
        // 700ms shifted by -500ms leaves 200ms, which the 400ms pipeline has already consumed. The client
        // deadline itself has not passed, so only the server can tell the client its deadline is lost.
        final TestServiceBlockingStub client =
                GrpcClients.builder(offsetDelayedServer.httpUri())
                           .responseTimeoutMillis(0)
                           .setHeader(GrpcHeaderNames.GRPC_TIMEOUT, "700m")
                           .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> requestTimeoutMillis(client))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
                });
    }

    @Test
    void anExceptionOtherThanATimeoutIsLeftAlone() {
        final TestServiceBlockingStub client =
                GrpcClients.builder(throwingServer.httpUri())
                           .responseTimeoutMillis(0)
                           .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> client.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.UNKNOWN);
                });
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
     * what its deadline already spent before writing the {@code grpc-timeout} header, and the server sets the
     * timeout relative to the start of the request.
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

    private static class DelayedResponseService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final ServiceRequestContext ctx = ServiceRequestContext.current();
            delayedRequestTimeoutMillis.set(ctx.requestTimeoutMillis());
            delayedRequestCancellation.set(ctx.whenRequestCancelling());
            ctx.eventLoop().schedule(
                    () -> TimeoutReportingService.reportTimeout(responseObserver), 500, TimeUnit.MILLISECONDS);
        }
    }
}
