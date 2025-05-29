/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryRuleWithContent;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingInputCallRequest;
import testing.grpc.Messages.StreamingInputCallResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceStub;

final class GrpcClientRetryTest {

    private static final AtomicInteger retryCounter = new AtomicInteger();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .build());
        }
    };

    @BeforeEach
    void setUp() {
        retryCounter.set(0);
    }

    @Test
    void childrenContextsHaveSameRpcRequest() {
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri())
                           .decorator(RetryingClient.newDecorator(retryRuleWithContent()))
                           .build(TestServiceBlockingStub.class);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SimpleResponse result = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(result.getUsername()).isEqualTo("my name");
            final ClientRequestContext context = captor.get();
            final List<RequestLogAccess> children = context.log().children();
            assertThat(children).hasSize(3);
            children.forEach(child -> {
                assertThat(context.rpcRequest()).isSameAs(child.context().rpcRequest());
            });
        }
    }

    @Test
    void retry_streamingOutputCall_withoutTrailers() {
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri())
                           .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
                           .build(TestServiceBlockingStub.class);
        final Iterator<StreamingOutputCallResponse> res =
                client.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance());
        assertThatThrownBy(res::next)
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("INTERNAL");
        assertThat(retryCounter).hasValue(1);
    }

    @Test
    void retry_streamingOutputCall_withTrailers() {
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri())
                           .decorator(RetryingClient.newDecorator(retryRuleWithContent()))
                           .build(TestServiceBlockingStub.class);
        final Iterator<StreamingOutputCallResponse> res =
                client.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance());
        int count = 0;
        while (res.hasNext()) {
            final StreamingOutputCallResponse next = res.next();
            assertThat(next.getPayload().getTypeValue()).isEqualTo(++count);
        }
        assertThat(count).isEqualTo(2);
        assertThat(retryCounter).hasValue(3);
    }

    @Test
    void retry_streamingInputCall_withoutTrailers() {
        final TestServiceStub client =
                GrpcClients.builder(server.httpUri())
                           .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
                           .build(TestServiceStub.class);
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final StreamObserver<StreamingInputCallRequest> req =
                client.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
                    @Override
                    public void onNext(StreamingInputCallResponse message) {}

                    @Override
                    public void onError(Throwable t) {
                        causeRef.set(t);
                    }

                    @Override
                    public void onCompleted() {}
                });

        req.onNext(StreamingInputCallRequest.newBuilder()
                                            .setPayload(Payload.newBuilder()
                                                               .setBody(ByteString.copyFromUtf8("12345"))
                                                               .build())
                                            .build());

        req.onNext(StreamingInputCallRequest.newBuilder()
                                            .setPayload(Payload.newBuilder()
                                                               .setBody(ByteString.copyFromUtf8("123456"))
                                                               .build())
                                            .build());
        req.onCompleted();

        await().untilAsserted(() -> {
            final Throwable cause = causeRef.get();
            assertThat(cause).isInstanceOf(StatusRuntimeException.class)
                             .hasMessageContaining("INTERNAL");
        });
        assertThat(retryCounter).hasValue(1);
    }

    @Test
    void retry_streamingInputCall_withTrailers() {
        final TestServiceStub client =
                GrpcClients.builder(server.httpUri())
                           .decorator(RetryingClient.newDecorator(retryRuleWithContent()))
                           .build(TestServiceStub.class);
        final AtomicInteger payloadSize = new AtomicInteger();
        final AtomicBoolean completed = new AtomicBoolean();
        final StreamObserver<StreamingInputCallRequest> req =
                client.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
                    @Override
                    public void onNext(StreamingInputCallResponse message) {
                        payloadSize.set(message.getAggregatedPayloadSize());
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                });

        req.onNext(StreamingInputCallRequest.newBuilder()
                                            .setPayload(Payload.newBuilder()
                                                               .setBody(ByteString.copyFromUtf8("12345"))
                                                               .build())
                                            .build());

        req.onNext(StreamingInputCallRequest.newBuilder()
                                            .setPayload(Payload.newBuilder()
                                                               .setBody(ByteString.copyFromUtf8("123456"))
                                                               .build())
                                            .build());
        req.onCompleted();

        await().untilTrue(completed);
        assertThat(payloadSize).hasValue(11);
        assertThat(retryCounter).hasValue(3);
    }

    @Test
    void retry_fullDuplex_withoutTrailers() {
        final TestServiceStub client =
                GrpcClients.builder(server.httpUri())
                           .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
                           .build(TestServiceStub.class);
        final List<String> responses = new ArrayList<>();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final StreamObserver<StreamingOutputCallRequest> req =
                client.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
                    @Override
                    public void onNext(StreamingOutputCallResponse message) {
                        responses.add(message.getPayload().getBody().toStringUtf8());
                    }

                    @Override
                    public void onError(Throwable t) {
                        causeRef.set(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        req.onNext(StreamingOutputCallRequest.newBuilder()
                                             .setPayload(Payload.newBuilder()
                                                                .setBody(ByteString.copyFromUtf8("12345"))
                                                                .build())
                                             .build());

        req.onNext(StreamingOutputCallRequest.newBuilder()
                                             .setPayload(Payload.newBuilder()
                                                                .setBody(ByteString.copyFromUtf8("67890"))
                                                                .build())
                                             .build());
        req.onCompleted();

        await().untilAsserted(() -> {
            final Throwable cause = causeRef.get();
            assertThat(cause).isInstanceOf(StatusRuntimeException.class)
                             .hasMessageContaining("INTERNAL");
        });
        // 200 status is returned for the first request.
        assertThat(retryCounter).hasValue(1);
        assertThat(responses).isEmpty();
    }

    @Test
    void retry_fullDuplex_withTrailers() {
        final TestServiceStub client =
                GrpcClients.builder(server.httpUri())
                           .decorator(RetryingClient.newDecorator(retryRuleWithContent()))
                           .build(TestServiceStub.class);
        final List<String> responses = new ArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final StreamObserver<StreamingOutputCallRequest> req =
                client.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
                    @Override
                    public void onNext(StreamingOutputCallResponse message) {
                        responses.add(message.getPayload().getBody().toStringUtf8());
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                });

        req.onNext(StreamingOutputCallRequest.newBuilder()
                                             .setPayload(Payload.newBuilder()
                                                                .setBody(ByteString.copyFromUtf8("12345"))
                                                                .build())
                                             .build());

        req.onNext(StreamingOutputCallRequest.newBuilder()
                                             .setPayload(Payload.newBuilder()
                                                                .setBody(ByteString.copyFromUtf8("67890"))
                                                                .build())
                                             .build());
        req.onCompleted();

        await().untilTrue(completed);
        assertThat(responses).containsExactly("12345", "67890");
        assertThat(retryCounter).hasValue(3);
    }

    private static RetryRuleWithContent<HttpResponse> retryRuleWithContent() {
        return RetryRuleWithContent
                .<HttpResponse>builder()
                .onGrpcTrailers((ctx, trailers) -> trailers.getInt(GrpcHeaderNames.GRPC_STATUS, -1) != 0)
                .thenBackoff();
    }

    private static class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            switch (retryCounter.getAndIncrement()) {
                case 0:
                    responseObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                case 1:
                    responseObserver.onNext(SimpleResponse.newBuilder().setUsername("my name").build());
                    responseObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                default:
                    responseObserver.onNext(SimpleResponse.newBuilder().setUsername("my name").build());
                    responseObserver.onCompleted();
                    break;
            }
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            switch (retryCounter.getAndIncrement()) {
                case 0:
                    responseObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                case 1:
                    responseObserver.onNext(
                            StreamingOutputCallResponse.newBuilder()
                                                       .setPayload(Payload.newBuilder().setTypeValue(1))
                                                       .build());
                    responseObserver.onError(new StatusException(Status.INTERNAL));
                    break;
                default:
                    responseObserver.onNext(
                            StreamingOutputCallResponse.newBuilder()
                                                       .setPayload(Payload.newBuilder().setTypeValue(1))
                                                       .build());
                    responseObserver.onNext(
                            StreamingOutputCallResponse.newBuilder()
                                                       .setPayload(Payload.newBuilder().setTypeValue(2))
                                                       .build());
                    responseObserver.onCompleted();
                    break;
            }
        }

        @Override
        public StreamObserver<StreamingInputCallRequest> streamingInputCall(
                StreamObserver<StreamingInputCallResponse> responseObserver) {

            return new StreamObserver<StreamingInputCallRequest>() {
                private int totalPayloadSize;

                @Override
                public void onNext(StreamingInputCallRequest message) {
                    totalPayloadSize += message.getPayload().getBody().size();
                }

                @Override
                public void onCompleted() {
                    switch (retryCounter.getAndIncrement()) {
                        case 0:
                            responseObserver.onError(new StatusException(Status.INTERNAL));
                            break;
                        case 1:
                            responseObserver.onNext(StreamingInputCallResponse.newBuilder()
                                                                              .setAggregatedPayloadSize(
                                                                                      totalPayloadSize)
                                                                              .build());
                            responseObserver.onError(new StatusException(Status.INTERNAL));
                            break;
                        default:
                            responseObserver.onNext(StreamingInputCallResponse.newBuilder()
                                                                              .setAggregatedPayloadSize(
                                                                                      totalPayloadSize)
                                                                              .build());
                            responseObserver.onCompleted();
                            break;
                    }
                }

                @Override
                public void onError(Throwable cause) {
                    responseObserver.onError(cause);
                }
            };
        }

        @Override
        public StreamObserver<StreamingOutputCallRequest> fullDuplexCall(
                StreamObserver<StreamingOutputCallResponse> responseObserver) {
            final int retryCount = retryCounter.getAndIncrement();
            return new StreamObserver<StreamingOutputCallRequest>() {

                @Override
                public void onNext(StreamingOutputCallRequest message) {
                    if (retryCount == 0) {
                        responseObserver.onError(new StatusException(Status.INTERNAL));
                    } else {
                        responseObserver.onNext(StreamingOutputCallResponse.newBuilder()
                                                                           .setPayload(message.getPayload())
                                                                           .build());
                    }
                }

                @Override
                public void onError(Throwable cause) {
                    responseObserver.onError(cause);
                }

                @Override
                public void onCompleted() {
                    if (retryCount == 1) {
                        responseObserver.onError(new StatusException(Status.INTERNAL));
                    } else {
                        responseObserver.onCompleted();
                    }
                }
            };
        }
    }
}
