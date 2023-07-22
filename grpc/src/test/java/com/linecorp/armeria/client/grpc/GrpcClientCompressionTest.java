/*
 * Copyright 2021 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Codec;
import io.grpc.Codec.Gzip;
import io.grpc.Codec.Identity;
import io.grpc.DecompressorRegistry;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.StreamingInputCallRequest;
import testing.grpc.Messages.StreamingInputCallResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class GrpcClientCompressionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                                  .build());
            sb.decorator(LoggingService.newDecorator());
        }
    };

    final Gzip gzip = new Gzip();

    @Test
    void compression_unary() throws InterruptedException {
        final TestServiceBlockingStub stub = GrpcClients.builder(server.httpUri())
                                                        .compressor(gzip)
                                                        .build(TestServiceBlockingStub.class);

        final Payload payload = Payload.newBuilder().setBody(ByteString.copyFromUtf8("Hello")).build();
        stub.unaryCall(SimpleRequest.newBuilder()
                                    .setPayload(payload)
                                    .build());
        ServiceRequestContext ctx = server.requestContextCaptor().take();
        RequestLog log = ctx.log().whenComplete().join();
        String encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isEqualTo(gzip.getMessageEncoding());

        // Override the client level compressor with CallOptions
        final TestServiceBlockingStub noCompression =
                stub.withCompression(Identity.NONE.getMessageEncoding());

        noCompression.unaryCall(SimpleRequest.newBuilder().setPayload(payload).build());
        ctx = server.requestContextCaptor().take();
        log = ctx.log().whenComplete().join();
        encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isNull();
    }

    @Test
    void compression_requestStreaming() throws InterruptedException {
        final TestServiceStub stub = GrpcClients.builder(server.httpUri())
                                                .compressor(gzip)
                                                .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        StreamObserver<StreamingInputCallRequest> input =
                stub.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
                    @Override
                    public void onNext(StreamingInputCallResponse value) {}

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                });
        input.onNext(StreamingInputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);
        ServiceRequestContext ctx = server.requestContextCaptor().take();
        RequestLog log = ctx.log().whenComplete().join();
        String encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isEqualTo(gzip.getMessageEncoding());

        // Override the client level compressor with CallOptions
        final TestServiceStub noCompression =
                stub.withCompression(Identity.NONE.getMessageEncoding());

        completed.set(false);
        input = noCompression.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
            @Override
            public void onNext(StreamingInputCallResponse value) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {
                completed.set(true);
            }
        });
        input.onNext(StreamingInputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);
        ctx = server.requestContextCaptor().take();
        log = ctx.log().whenComplete().join();
        encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isNull();
    }

    @Test
    void compression_responseStreaming() throws InterruptedException {
        final TestServiceStub stub = GrpcClients.builder(server.httpUri())
                                                .compressor(gzip)
                                                .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        stub.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance(),
                                 new StreamObserver<StreamingOutputCallResponse>() {
                                     @Override
                                     public void onNext(StreamingOutputCallResponse value) {}

                                     @Override
                                     public void onError(Throwable t) {}

                                     @Override
                                     public void onCompleted() {
                                         completed.set(true);
                                     }
                                 });
        await().untilTrue(completed);
        ServiceRequestContext ctx = server.requestContextCaptor().take();
        RequestLog log = ctx.log().whenComplete().join();
        String encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isEqualTo(gzip.getMessageEncoding());

        // Override the client level compressor with CallOptions
        final TestServiceStub noCompression =
                stub.withCompression(Identity.NONE.getMessageEncoding());

        completed.set(false);
        noCompression.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance(),
                                          new StreamObserver<StreamingOutputCallResponse>() {
                                              @Override
                                              public void onNext(StreamingOutputCallResponse value) {}

                                              @Override
                                              public void onError(Throwable t) {}

                                              @Override
                                              public void onCompleted() {
                                                  completed.set(true);
                                              }
                                          });
        await().untilTrue(completed);
        ctx = server.requestContextCaptor().take();
        log = ctx.log().whenComplete().join();
        encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isNull();
    }

    @Test
    void compression_bidiStreaming() throws InterruptedException {
        final TestServiceStub stub = GrpcClients.builder(server.httpUri())
                                                .compressor(gzip)
                                                .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        StreamObserver<StreamingOutputCallRequest> input =
                stub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
                    @Override
                    public void onNext(StreamingOutputCallResponse value) {}

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                });
        input.onNext(StreamingOutputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);
        ServiceRequestContext ctx = server.requestContextCaptor().take();
        RequestLog log = ctx.log().whenComplete().join();
        String encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isEqualTo(gzip.getMessageEncoding());

        // Override the client level compressor with CallOptions
        final TestServiceStub noCompression =
                stub.withCompression(Identity.NONE.getMessageEncoding());

        completed.set(false);
        input = noCompression.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
            @Override
            public void onNext(StreamingOutputCallResponse value) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {
                completed.set(true);
            }
        });
        input.onNext(StreamingOutputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);
        ctx = server.requestContextCaptor().take();
        log = ctx.log().whenComplete().join();
        encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isNull();
    }

    @Test
    void decompressionRegistry_unary() throws InterruptedException {
        final TestServiceBlockingStub stub =
                GrpcClients.builder(server.httpUri())
                           .decompressorRegistry(DecompressorRegistry.emptyInstance()
                                                                     .with(Codec.Identity.NONE, true))
                           .build(TestServiceBlockingStub.class);

        final Payload payload = Payload.newBuilder().setBody(ByteString.copyFromUtf8("Hello")).build();
        stub.unaryCall(SimpleRequest.newBuilder().setPayload(payload).build());
        ServiceRequestContext ctx = server.requestContextCaptor().take();
        RequestLog log = ctx.log().whenComplete().join();
        String encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);
        assertThat(encoding).isEqualTo("identity");

        final TestServiceBlockingStub decompressingStub = GrpcClients.builder(server.httpUri())
                                                                     // Use the default DecompressorRegistry
                                                                     .build(TestServiceBlockingStub.class);

        final Payload payload0 = Payload.newBuilder().setBody(ByteString.copyFromUtf8("Hello")).build();
        decompressingStub.unaryCall(SimpleRequest.newBuilder().setPayload(payload0).build());
        ctx = server.requestContextCaptor().take();
        log = ctx.log().whenComplete().join();
        encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);
        assertThat(encoding).isEqualTo("gzip");
    }

    @Test
    void decompressionRegistry_requestStreaming() throws InterruptedException {
        final TestServiceStub stub =
                GrpcClients.builder(server.httpUri())
                           .decompressorRegistry(DecompressorRegistry.emptyInstance()
                                                                     .with(Codec.Identity.NONE, true))
                           .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        StreamObserver<StreamingInputCallRequest> input =
                stub.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
                    @Override
                    public void onNext(StreamingInputCallResponse value) {}

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                });
        input.onNext(StreamingInputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);

        ServiceRequestContext ctx = server.requestContextCaptor().take();
        RequestLog log = ctx.log().whenComplete().join();
        String encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);
        assertThat(encoding).isEqualTo("identity");

        final TestServiceStub decompressingStub = GrpcClients.builder(server.httpUri())
                                                             // Use the default DecompressorRegistry
                                                             .build(TestServiceStub.class);

        completed.set(false);
        input = decompressingStub.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
            @Override
            public void onNext(StreamingInputCallResponse value) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {
                completed.set(true);
            }
        });
        input.onNext(StreamingInputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);
        ctx = server.requestContextCaptor().take();
        log = ctx.log().whenComplete().join();
        encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);
        assertThat(encoding).isEqualTo("gzip");
    }

    @Test
    void decompressionRegistry_responseStreaming() throws InterruptedException {
        final TestServiceStub stub =
                GrpcClients.builder(server.httpUri())
                           .decompressorRegistry(DecompressorRegistry.emptyInstance()
                                                                     .with(Codec.Identity.NONE, true))
                           .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        StreamObserver<StreamingInputCallRequest> input =
                stub.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
                    @Override
                    public void onNext(StreamingInputCallResponse value) {}

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                });
        input.onNext(StreamingInputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);

        ServiceRequestContext ctx = server.requestContextCaptor().take();
        RequestLog log = ctx.log().whenComplete().join();
        String encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);
        assertThat(encoding).isEqualTo("identity");

        final TestServiceStub decompressingStub = GrpcClients.builder(server.httpUri())
                                                             // Use the default DecompressorRegistry
                                                             .build(TestServiceStub.class);

        completed.set(false);
        input = decompressingStub.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
            @Override
            public void onNext(StreamingInputCallResponse value) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {
                completed.set(true);
            }
        });
        input.onNext(StreamingInputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);
        ctx = server.requestContextCaptor().take();
        log = ctx.log().whenComplete().join();
        encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);
        assertThat(encoding).isEqualTo("gzip");
    }

    @Test
    void decompressionRegistry_bidiStreaming() throws InterruptedException {
        final TestServiceStub stub =
                GrpcClients.builder(server.httpUri())
                           .decompressorRegistry(DecompressorRegistry.emptyInstance()
                                                                     .with(Codec.Identity.NONE, true))
                           .build(TestServiceStub.class);

        final AtomicBoolean completed = new AtomicBoolean();
        StreamObserver<StreamingOutputCallRequest> input =
                stub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
                    @Override
                    public void onNext(StreamingOutputCallResponse value) {}

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                });
        input.onNext(StreamingOutputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);

        ServiceRequestContext ctx = server.requestContextCaptor().take();
        RequestLog log = ctx.log().whenComplete().join();
        String encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);
        assertThat(encoding).isEqualTo("identity");

        final TestServiceStub decompressingStub = GrpcClients.builder(server.httpUri())
                                                             // Use the default DecompressorRegistry
                                                             .build(TestServiceStub.class);

        completed.set(false);
        input = decompressingStub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
            @Override
            public void onNext(StreamingOutputCallResponse value) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {
                completed.set(true);
            }
        });
        input.onNext(StreamingOutputCallRequest.getDefaultInstance());
        input.onCompleted();
        await().untilTrue(completed);
        ctx = server.requestContextCaptor().take();
        log = ctx.log().whenComplete().join();
        encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);
        assertThat(encoding).isEqualTo("gzip");
    }
}
