/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClientOptions;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.FlowControlTestServiceGrpc.FlowControlTestServiceImplBase;
import com.linecorp.armeria.grpc.testing.FlowControlTestServiceGrpc.FlowControlTestServiceStub;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.linecorp.armeria.testing.server.ServerRule;

import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

public class GrpcFlowControlTest {

    private static final int TOTAL_NUM_MESSAGES = 10;
    private static final int CAPPED_NUM_MESSAGES = 3;

    // Large enough payload to trigger flow control.
    private static Payload PAYLOAD;
    private static SimpleRequest REQUEST;
    private static SimpleResponse RESPONSE;

    @BeforeClass
    public static void createMessages() {
        PAYLOAD = Payload.newBuilder()
                         .setBody(ByteString.copyFromUtf8(Strings.repeat("a", 5 * 1024 * 1024)))
                         .build();
        REQUEST = SimpleRequest.newBuilder()
                               .setPayload(PAYLOAD)
                               .build();
        RESPONSE = SimpleResponse.newBuilder()
                                 .setPayload(PAYLOAD)
                                 .build();
    }

    @AfterClass
    public static void destroyMessages() {
        // Dereference to reduce the memory pressure on the VM.
        PAYLOAD = null;
        REQUEST = null;
        RESPONSE = null;
    }

    static class FlowControlService extends FlowControlTestServiceImplBase {
        @Override
        public StreamObserver<SimpleRequest> noBackPressure(StreamObserver<SimpleResponse> responseObserver) {
            final AtomicInteger numRequests = new AtomicInteger();
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    numRequests.incrementAndGet();
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    assertThat(numRequests).hasValue(TOTAL_NUM_MESSAGES);
                    for (int i = 0; i < TOTAL_NUM_MESSAGES; i++) {
                        responseObserver.onNext(SimpleResponse.getDefaultInstance());
                    }
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public StreamObserver<SimpleRequest> serverBackPressure(
                StreamObserver<SimpleResponse> rawResponseObserver) {
            final ServerCallStreamObserver<SimpleResponse> responseObserver =
                    (ServerCallStreamObserver<SimpleResponse>) rawResponseObserver;
            responseObserver.disableAutoInboundFlowControl();
            final AtomicInteger numRequests = new AtomicInteger();
            responseObserver.request(1);
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    if (numRequests.incrementAndGet() < CAPPED_NUM_MESSAGES) {
                        responseObserver.request(1);
                    } else {
                        for (int i = 0; i < TOTAL_NUM_MESSAGES; i++) {
                            responseObserver.onNext(SimpleResponse.getDefaultInstance());
                        }
                        responseObserver.onCompleted();
                    }
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            };
        }

        @Override
        public StreamObserver<SimpleRequest> clientBackPressure(
                StreamObserver<SimpleResponse> rawResponseObserver) {
            final AtomicInteger numRequests = new AtomicInteger();
            final AtomicInteger numResponses = new AtomicInteger();
            final AtomicBoolean done = new AtomicBoolean();
            final ServerCallStreamObserver<SimpleResponse> responseObserver =
                    (ServerCallStreamObserver<SimpleResponse>) rawResponseObserver;
            responseObserver.setOnReadyHandler(() -> {
                if (numResponses.get() < TOTAL_NUM_MESSAGES && !done.get()) {
                    numResponses.incrementAndGet();
                    responseObserver.onNext(RESPONSE);
                }
            });
            for (int i = 0; i < TOTAL_NUM_MESSAGES; i++) {
                if (responseObserver.isReady()) {
                    numResponses.incrementAndGet();
                    responseObserver.onNext(RESPONSE);
                } else {
                    break;
                }
            }
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    numRequests.incrementAndGet();
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    done.set(true);
                    assertThat(numRequests).hasValue(TOTAL_NUM_MESSAGES);
                    assertThat(numResponses).hasValueBetween(CAPPED_NUM_MESSAGES, CAPPED_NUM_MESSAGES + 2);
                    responseObserver.onCompleted();
                }
            };
        }
    }

    @ClassRule
    public static ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.defaultMaxRequestLength(0);
            sb.defaultRequestTimeoutMillis(0);

            sb.serviceUnder("/", new GrpcServiceBuilder()
                    .addService(new FlowControlService())
                    .setMaxInboundMessageSizeBytes(Integer.MAX_VALUE)
                    .build());
        }
    };

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(15, TimeUnit.SECONDS));

    private FlowControlTestServiceStub client;

    @Before
    public void setUp() {
        client = new ClientBuilder(server.uri(GrpcSerializationFormats.PROTO, "/"))
                .defaultMaxResponseLength(0)
                .defaultResponseTimeoutMillis(0)
                .option(GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES.newValue(Integer.MAX_VALUE))
                .build(FlowControlTestServiceStub.class);
    }

    @Test
    public void noBackPressure() {
        final AtomicInteger numResponses = new AtomicInteger();
        final AtomicBoolean done = new AtomicBoolean();
        final StreamObserver<SimpleRequest> req = client.noBackPressure(new StreamObserver<SimpleResponse>() {
            @Override
            public void onNext(SimpleResponse value) {
                numResponses.incrementAndGet();
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
                done.set(true);
            }
        });
        for (int i = 0; i < TOTAL_NUM_MESSAGES; i++) {
            req.onNext(SimpleRequest.getDefaultInstance());
        }
        req.onCompleted();
        await().untilAsserted(() -> assertThat(done).isTrue());
        assertThat(numResponses).hasValue(TOTAL_NUM_MESSAGES);
    }

    @Test
    public void serverBackPressure() {
        final AtomicInteger numRequests = new AtomicInteger();
        final AtomicInteger numResponses = new AtomicInteger();
        final AtomicBoolean done = new AtomicBoolean();
        final ClientCallStreamObserver<SimpleRequest> req =
                (ClientCallStreamObserver<SimpleRequest>) client.serverBackPressure(
                        new ClientResponseObserver<SimpleRequest, SimpleResponse>() {
                            @Override
                            public void onNext(SimpleResponse value) {
                                numResponses.incrementAndGet();
                            }

                            @Override
                            public void onError(Throwable t) {
                            }

                            @Override
                            public void onCompleted() {
                                done.set(true);
                            }

                            @Override
                            public void beforeStart(ClientCallStreamObserver<SimpleRequest> requestStream) {
                                requestStream.setOnReadyHandler(() -> {
                                    if (numRequests.get() < TOTAL_NUM_MESSAGES) {
                                        numRequests.incrementAndGet();
                                        requestStream.onNext(REQUEST);
                                    }
                                });
                            }
                        });
        for (int i = 0; i < TOTAL_NUM_MESSAGES; i++) {
            if (req.isReady()) {
                numRequests.incrementAndGet();
                req.onNext(REQUEST);
            } else {
                break;
            }
        }
        await().untilAsserted(() -> assertThat(done).isTrue());
        // Flow control happens on the second request, and an extra message is often sent after the last
        // requested one since there will still be some space in the last flow control window, which results
        // in two more than our expected cap.
        assertThat(numRequests).hasValueBetween(CAPPED_NUM_MESSAGES, CAPPED_NUM_MESSAGES + 2);
        assertThat(numResponses).hasValue(TOTAL_NUM_MESSAGES);
    }

    @Test
    public void clientBackPressure() {
        final AtomicInteger numResponses = new AtomicInteger();
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicBoolean clientClosed = new AtomicBoolean();
        client.clientBackPressure(
                new ClientResponseObserver<SimpleRequest, SimpleResponse>() {
                    private ClientCallStreamObserver<SimpleRequest> requestStream;

                    @Override
                    public void onNext(SimpleResponse value) {
                        if (numResponses.incrementAndGet() < CAPPED_NUM_MESSAGES) {
                            requestStream.request(1);
                        } else {
                            if (!clientClosed.get()) {
                                for (int i = 0; i < TOTAL_NUM_MESSAGES; i++) {
                                    requestStream.onNext(SimpleRequest.getDefaultInstance());
                                }
                                requestStream.onCompleted();
                                clientClosed.set(true);
                            }
                            requestStream.request(1);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                        done.set(true);
                    }

                    @Override
                    public void beforeStart(ClientCallStreamObserver<SimpleRequest> requestStream) {
                        this.requestStream = requestStream;
                        requestStream.disableAutoInboundFlowControl();
                    }
                });

        await().untilAsserted(() -> assertThat(done).isTrue());
        // Flow control happens on the second request, and an extra message is often sent after the last
        // requested one since there will still be some space in the last flow control window, which results
        // in two more than our expected cap.
        assertThat(numResponses).hasValueBetween(CAPPED_NUM_MESSAGES, CAPPED_NUM_MESSAGES + 2);
    }
}
