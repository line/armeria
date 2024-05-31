/*
 * Copyright 2017 LINE Corporation
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

import static com.linecorp.armeria.internal.common.grpc.GrpcTestUtil.REQUEST_MESSAGE;
import static com.linecorp.armeria.internal.common.grpc.GrpcTestUtil.RESPONSE_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static testing.grpc.Messages.PayloadType.COMPRESSABLE;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import com.google.protobuf.ByteString;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcCallOptions;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.internal.common.RequestTargetCache;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.common.grpc.MetadataUtil;
import com.linecorp.armeria.internal.common.grpc.StreamRecorder;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.internal.common.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.internal.common.util.EventLoopThread;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.SecurityLevel;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderValues;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.CompressionType;
import testing.grpc.Messages.EchoStatus;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.ResponseParameters;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingInputCallRequest;
import testing.grpc.Messages.StreamingInputCallResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceStub;
import testing.grpc.UnimplementedServiceGrpc;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceBlockingStub;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceImplBase;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceStub;

class GrpcClientTest {

    /**
     * Must be at least {@link #unaryPayloadLength()}, plus some to account for encoding overhead.
     */
    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    private static final Empty EMPTY = Empty.getDefaultInstance();

    private static final CallOptions.Key<String> MY_CALL_OPTION_KEY =
            CallOptions.Key.create("my-call-option");

    private static final AtomicReference<HttpHeaders> CLIENT_HEADERS_CAPTURE = new AtomicReference<>();
    private static final AtomicReference<HttpHeaders> SERVER_TRAILERS_CAPTURE = new AtomicReference<>();

    private static final Key<Int32Value> INT_32_VALUE_KEY =
            ProtoUtils.keyForProto(Int32Value.getDefaultInstance());

    private static final Key<String> CUSTOM_VALUE_KEY = Key.of("custom-header",
                                                               Metadata.ASCII_STRING_MARSHALLER);

    private static class UnitTestServiceImpl extends UnitTestServiceImplBase {
        @Override
        public void errorNoMessage(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(Status.ABORTED.asRuntimeException());
        }

        @Override
        public void errorWithMessage(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(buildExceptionWithMeta());
        }

        @Override
        public StreamObserver<SimpleRequest> errorFromClient(StreamObserver<SimpleResponse> responseObserver) {
            return new StreamObserver<SimpleRequest>() {

                @Override
                public void onNext(SimpleRequest value) {
                    responseObserver.onNext(RESPONSE_MESSAGE);
                }

                @Override
                public void onError(Throwable t) { }

                @Override
                public void onCompleted() { }
            };
        }

        static StatusRuntimeException buildExceptionWithMeta() {
            final Metadata meta = new Metadata();
            meta.put(INT_32_VALUE_KEY, Int32Value.newBuilder().setValue(1).build());
            meta.put(CUSTOM_VALUE_KEY, "string");
            return Status.DATA_LOSS.withDescription("data loss!")
                                   .asRuntimeException(meta);
        }
    }

    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.workerGroup(1);
            sb.maxRequestLength(MAX_MESSAGE_SIZE);
            sb.idleTimeoutMillis(0);
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            final ServerInterceptor clientHeaderCaptureInterceptor = new ServerInterceptor() {
                @Override
                public <REQ, RESP> Listener<REQ> interceptCall(
                        ServerCall<REQ, RESP> call,
                        Metadata requestHeaders,
                        ServerCallHandler<REQ, RESP> next) {
                    final HttpHeadersBuilder fromClient = HttpHeaders.builder();
                    MetadataUtil.fillHeaders(requestHeaders, fromClient);
                    CLIENT_HEADERS_CAPTURE.set(fromClient.build());
                    return next.startCall(
                            new SimpleForwardingServerCall<REQ, RESP>(call) {
                                @Override
                                public void close(Status status, Metadata trailers) {
                                    trailers.merge(requestHeaders);
                                    super.close(status, trailers);
                                }
                            }, requestHeaders);
                }
            };
            sb.serviceUnder("/",
                            GrpcService.builder()
                                       .addService(new TestServiceImpl(
                                               Executors.newSingleThreadScheduledExecutor()))
                                       .addService(new UnitTestServiceImpl())
                                       .intercept(clientHeaderCaptureInterceptor)
                                       .maxRequestMessageLength(MAX_MESSAGE_SIZE)
                                       .maxResponseMessageLength(MAX_MESSAGE_SIZE)
                                       .useClientTimeoutHeader(false)
                                       .build()
                                       .decorate((service, ctx, req) -> {
                                           final HttpResponse res = service.serve(ctx, req);
                                           return res.peekTrailers(SERVER_TRAILERS_CAPTURE::set);
                                       }));
        }
    };

    private final BlockingQueue<RequestLog> requestLogQueue = new LinkedTransferQueue<>();
    private TestServiceBlockingStub blockingStub;
    private TestServiceStub asyncStub;
    private UnitTestServiceBlockingStub unitTestBlockingStub;
    private UnitTestServiceStub unitTestAsyncStub;

    @BeforeEach
    void setUp() {
        RequestTargetCache.clearCachedPaths();
        requestLogQueue.clear();
        final DecoratingHttpClientFunction requestLogRecorder = (delegate, ctx, req) -> {
            ctx.log().whenComplete().thenAccept(requestLogQueue::add);
            return delegate.execute(ctx, req);
        };

        final URI uri = server.httpUri(GrpcSerializationFormats.PROTO);
        blockingStub = GrpcClients.builder(uri)
                                  .maxResponseLength(MAX_MESSAGE_SIZE)
                                  .decorator(requestLogRecorder)
                                  .build(TestServiceBlockingStub.class);
        asyncStub = GrpcClients.builder(uri.getScheme(), server.httpEndpoint())
                               .decorator(requestLogRecorder)
                               .build(TestServiceStub.class);
        unitTestBlockingStub = GrpcClients.builder(uri)
                                          .maxResponseLength(MAX_MESSAGE_SIZE)
                                          .decorator(requestLogRecorder)
                                          .build(UnitTestServiceBlockingStub.class);
        unitTestAsyncStub = GrpcClients.builder(uri.getScheme(), server.httpEndpoint())
                                       .decorator(requestLogRecorder)
                                       .build(UnitTestServiceStub.class);
    }

    @AfterEach
    void tearDown() {
        CLIENT_HEADERS_CAPTURE.set(null);
        SERVER_TRAILERS_CAPTURE.set(null);
    }

    @Test
    void emptyUnary() throws Exception {
        assertThat(blockingStub.emptyCall(EMPTY)).isEqualTo(EMPTY);
        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(EMPTY);
            assertThat(rpcRes.get()).isEqualTo(EMPTY);
        });
        await().untilAsserted(() -> assertThat(RequestTargetCache.cachedClientPaths())
                .contains("/armeria.grpc.testing.TestService/EmptyCall"));
    }

    @Test
    void emptyUnary_grpcWeb() throws Exception {
        final TestServiceBlockingStub stub =
                GrpcClients.builder(server.httpUri())
                           .serializationFormat(GrpcSerializationFormats.PROTO_WEB)
                           .build(TestServiceBlockingStub.class);
        assertThat(stub.emptyCall(EMPTY)).isEqualTo(EMPTY);
    }

    @Test
    void contextCaptorBlocking() {
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            blockingStub.emptyCall(EMPTY);
            final ClientRequestContext ctx = ctxCaptor.get();
            assertThat(ctx.path()).isEqualTo("/armeria.grpc.testing.TestService/EmptyCall");
        }
    }

    @Test
    void contextCaptorAsync() {
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            asyncStub.emptyCall(EMPTY, StreamRecorder.create());
            final ClientRequestContext ctx = ctxCaptor.get();
            assertThat(ctx.path()).isEqualTo("/armeria.grpc.testing.TestService/EmptyCall");
        }
    }

    @Test
    void callOptionsExecutorIsRespected() {
        final ExecutorService executor = Executors.newSingleThreadExecutor(
                ThreadFactories.builder("my-calloptions-executor-3")
                    .daemon(true)
                    .eventLoop(false)
                    .build());

        final TestServiceGrpc.TestServiceStub asyncStubWithExecutor = asyncStub.withExecutor(executor);
        final AtomicReference<String> onMessageThread = new AtomicReference<>();
        final AtomicReference<String> onCompleteThread = new AtomicReference<>();
        asyncStubWithExecutor.emptyCall(EMPTY, new StreamObserver<Empty>() {
            @Override
            public void onNext(Empty value) {
                onMessageThread.set(Thread.currentThread().getName());
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
                onCompleteThread.set(Thread.currentThread().getName());
            }
        });

        await().untilAtomic(onMessageThread, startsWith("my-calloptions-executor-3"));
        await().untilAtomic(onCompleteThread, startsWith("my-calloptions-executor-3"));

        executor.shutdownNow();
    }

    @Test
    void callExecutorIsEventLoopByDefault() {
        final AtomicReference<Thread> onMessageThread = new AtomicReference<>();
        final AtomicReference<Thread> onCompleteThread = new AtomicReference<>();
        asyncStub.emptyCall(EMPTY, new StreamObserver<Empty>() {
            @Override
            public void onNext(Empty value) {
                onMessageThread.set(Thread.currentThread());
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
                onCompleteThread.set(Thread.currentThread());
            }
        });

        await().untilAtomic(onMessageThread, instanceOf(EventLoopThread.class));
        await().untilAtomic(onCompleteThread, instanceOf(EventLoopThread.class));
    }

    void grpcCallOptions() {
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            blockingStub
                    .withOption(MY_CALL_OPTION_KEY, "foo")
                    .emptyCall(EMPTY);
            final ClientRequestContext ctx = ctxCaptor.get();
            assertThat(GrpcCallOptions.get(ctx).getOption(MY_CALL_OPTION_KEY)).isEqualTo("foo");
        }
    }

    @Test
    void largeUnary() throws Exception {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(314159)
                             .setResponseType(COMPRESSABLE)
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFrom(new byte[271828])))
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[314159])))
                              .build();

        assertThat(blockingStub.unaryCall(request)).isEqualTo(goldenResponse);
        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(rpcRes.get()).isEqualTo(goldenResponse);
        });
        await().untilAsserted(() -> assertThat(RequestTargetCache.cachedClientPaths())
                .contains("/armeria.grpc.testing.TestService/UnaryCall"));
    }

    @Test
    void largeUnary_grpcWeb() throws Exception {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(314159)
                             .setResponseCompression(CompressionType.GZIP)
                             .setResponseType(COMPRESSABLE)
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFrom(new byte[271828])))
                             .build();
        final SimpleResponse simpleResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[314159])))
                              .build();
        final TestServiceBlockingStub stub =
                GrpcClients.newClient(server.httpUri(GrpcSerializationFormats.PROTO_WEB),
                                      TestServiceBlockingStub.class);
        assertThat(stub.unaryCall(request)).isEqualTo(simpleResponse);
    }

    @ParameterizedTest
    @EnumSource(value = CompressionType.class, names = { "NONE", "GZIP" })
    void largeWebText(CompressionType compressionType) throws Exception {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(314159)
                             .setResponseType(COMPRESSABLE)
                             .setResponseCompression(compressionType)
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFrom(new byte[271828])))
                             .build();
        final SimpleResponse simpleResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[314159])))
                              .build();
        final TestServiceBlockingStub stub =
                GrpcClients.newClient(server.httpUri(GrpcSerializationFormats.PROTO_WEB_TEXT),
                                      TestServiceBlockingStub.class);
        assertThat(stub.unaryCall(request)).isEqualTo(simpleResponse);
    }

    @Test
    void largeUnary_unsafe() throws Exception {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(314159)
                             .setResponseType(COMPRESSABLE)
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFrom(new byte[271828])))
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[314159])))
                              .build();

        final TestServiceStub stub =
                GrpcClients.builder(server.httpUri())
                           .enableUnsafeWrapResponseBuffers(true)
                           .build(TestServiceStub.class);

        final BlockingQueue<Object> resultQueue = new LinkedTransferQueue<>();
        stub.unaryCall(request, new StreamObserver<SimpleResponse>() {
            @Override
            public void onNext(SimpleResponse value) {
                try {
                    final ClientRequestContext ctx = ClientRequestContext.current();
                    assertThat(value).isEqualTo(goldenResponse);
                    final ByteBuf buf = ctx.attr(GrpcUnsafeBufferUtil.BUFFERS).get(value);
                    assertThat(buf.refCnt()).isNotZero();
                    GrpcUnsafeBufferUtil.releaseBuffer(value, ctx);
                    assertThat(buf.refCnt()).isZero();
                } catch (Throwable t) {
                    resultQueue.add(t);
                }
            }

            @Override
            public void onError(Throwable t) {
                resultQueue.add(t);
            }

            @Override
            public void onCompleted() {
                resultQueue.add("OK");
            }
        });

        final Object result = resultQueue.poll(10, TimeUnit.SECONDS);
        if (result instanceof Throwable) {
            Exceptions.throwUnsafely((Throwable) result);
        } else {
            assertThat(result).isEqualTo("OK");
            assertThat(resultQueue.poll(1, TimeUnit.SECONDS)).isNull();
        }
    }

    @Test
    void serverStreaming() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder()
                                                                    .setSize(31415))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(9))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(2653))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(58979))
                                          .build();
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[9])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[2653])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[58979])))
                                           .build());

        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        asyncStub.streamingOutputCall(request, recorder);
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).containsExactlyElementsOf(goldenResponses);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(rpcRes.get()).isEqualTo(goldenResponses.get(0));
        });
        await().untilAsserted(() -> assertThat(RequestTargetCache.cachedClientPaths())
                .contains("/armeria.grpc.testing.TestService/StreamingOutputCall"));
    }

    @Test
    void serverStreaming_blocking() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder()
                                                                    .setSize(31415)
                                                                    .setIntervalUs(1000))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(9)
                                                                                   .setIntervalUs(1000))
                                          .build();
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[9])))
                                           .build());

        final List<StreamingOutputCallResponse> responses = new ArrayList<>();
        final Iterator<StreamingOutputCallResponse> it = blockingStub.streamingOutputCall(request);
        while (it.hasNext()) {
            responses.add(it.next());
        }

        assertThat(responses).containsExactlyElementsOf(goldenResponses);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(rpcRes.get()).isEqualTo(goldenResponses.get(0));
        });
    }

    @Test
    void clientStreaming() throws Exception {
        final List<StreamingInputCallRequest> requests = Arrays.asList(
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[27182])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[8])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[1828])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[45904])))
                                         .build());
        final StreamingInputCallResponse goldenResponse =
                StreamingInputCallResponse.newBuilder()
                                          .setAggregatedPayloadSize(74922)
                                          .build();

        final StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingInputCallRequest> requestObserver =
                asyncStub.streamingInputCall(responseObserver);
        for (StreamingInputCallRequest request : requests) {
            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();
        assertThat(responseObserver.firstValue().get()).isEqualTo(goldenResponse);
        responseObserver.awaitCompletion();

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(requests.get(0));
            assertThat(rpcRes.get()).isEqualTo(goldenResponse);
        });
        await().untilAsserted(() -> assertThat(RequestTargetCache.cachedClientPaths())
                .contains("/armeria.grpc.testing.TestService/StreamingInputCall"));
    }

    @Test
    void pingPong() throws Exception {
        final List<StreamingOutputCallRequest> requests = Arrays.asList(
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(31415))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[27182])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(9))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[8])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(2653))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[1828])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(58979))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[45904])))
                                          .build());
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[9])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[2653])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[58979])))
                                           .build());

        final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(5);
        final StreamObserver<StreamingOutputCallRequest> requestObserver =
                asyncStub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
                    @Override
                    public void onNext(StreamingOutputCallResponse response) {
                        queue.add(response);
                    }

                    @Override
                    public void onError(Throwable t) {
                        queue.add(t);
                    }

                    @Override
                    public void onCompleted() {
                        queue.add("Completed");
                    }
                });
        for (int i = 0; i < requests.size(); i++) {
            assertThat(queue.peek()).isNull();
            requestObserver.onNext(requests.get(i));
            assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo(
                    goldenResponses.get(i));
        }
        requestObserver.onCompleted();
        assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo("Completed");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(requests.get(0));
            assertThat(rpcRes.get()).isEqualTo(goldenResponses.get(0));
        });
        await().untilAsserted(() -> assertThat(RequestTargetCache.cachedClientPaths())
                .contains("/armeria.grpc.testing.TestService/FullDuplexCall"));
    }

    @Test
    void emptyStream() throws Exception {
        final StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestObserver =
                asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onCompleted();
        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).isEmpty();
            assertThat(rpcRes.get()).isNull();
        });
    }

    @Test
    void cancelAfterBegin() throws Exception {
        final StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingInputCallRequest> requestObserver =
                asyncStub.streamingInputCall(responseObserver);
        requestObserver.onError(new RuntimeException());
        responseObserver.awaitCompletion();
        assertThat(responseObserver.getValues()).isEmpty();
        assertThat(GrpcExceptionHandlerFunction.of()
                                               .apply(null, responseObserver.getError(), null)
                                               .getCode()).isEqualTo(Code.CANCELLED);

        final RequestLog log = requestLogQueue.take();
        assertThat(log.isComplete()).isTrue();
        assertThat(log.responseContent()).isInstanceOf(RpcResponse.class);
        final Throwable cause = ((RpcResponse) log.responseContent()).cause();
        assertThat(cause).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) cause).getStatus().getCode()).isEqualTo(Code.CANCELLED);
    }

    @Test
    void cancelAfterFirstResponse() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(31415))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[27182])))
                                          .build();
        final StreamingOutputCallResponse goldenResponse =
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build();

        final StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestObserver =
                asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onNext(request);
        await().untilAsserted(() -> assertThat(responseObserver.firstValue().get()).isEqualTo(goldenResponse));
        requestObserver.onError(new RuntimeException());
        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(responseObserver.getValues()).hasSize(1);
        assertThat(GrpcExceptionHandlerFunction.of()
                                               .apply(null, responseObserver.getError(), null)
                                               .getCode()).isEqualTo(Code.CANCELLED);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(grpcStatus.getCode()).isEqualTo(Code.CANCELLED);
        });
    }

    @Test
    void fullDuplexCallShouldSucceed() throws Exception {
        // Build the request.
        final List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
        final StreamingOutputCallRequest.Builder streamingOutputBuilder =
                StreamingOutputCallRequest.newBuilder();
        streamingOutputBuilder.setResponseType(COMPRESSABLE);
        for (Integer size : responseSizes) {
            streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
        }
        final StreamingOutputCallRequest request =
                streamingOutputBuilder.build();

        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestStream =
                asyncStub.fullDuplexCall(recorder);

        final int numRequests = 10;
        final List<StreamingOutputCallRequest> requests =
                new ArrayList<>(numRequests);
        for (int ix = numRequests; ix > 0; --ix) {
            requests.add(request);
            requestStream.onNext(request);
        }
        requestStream.onCompleted();
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).hasSize(responseSizes.size() * numRequests);
        for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
            final StreamingOutputCallResponse response = recorder.getValues().get(ix);
            assertThat(response.getPayload().getType()).isEqualTo(COMPRESSABLE);
            final int length = response.getPayload().getBody().size();
            final int expectedSize = responseSizes.get(ix % responseSizes.size());
            assertThat(length).withFailMessage("comparison failed at index " + ix)
                              .isEqualTo(expectedSize);
        }

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(requests.get(0));
            assertThat(rpcRes.get()).isEqualTo(recorder.getValues().get(0));
        });
    }

    @Test
    void halfDuplexCallShouldSucceed() throws Exception {
        // Build the request.
        final List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
        final StreamingOutputCallRequest.Builder streamingOutputBuilder =
                StreamingOutputCallRequest.newBuilder();
        streamingOutputBuilder.setResponseType(COMPRESSABLE);
        for (Integer size : responseSizes) {
            streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
        }
        final StreamingOutputCallRequest request =
                streamingOutputBuilder.build();

        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestStream = asyncStub.halfDuplexCall(recorder);

        final int numRequests = 10;
        final List<StreamingOutputCallRequest> requests = new ArrayList<>(numRequests);
        for (int ix = numRequests; ix > 0; --ix) {
            requests.add(request);
            requestStream.onNext(request);
        }
        requestStream.onCompleted();
        recorder.awaitCompletion();

        assertSuccess(recorder);
        assertThat(recorder.getValues()).hasSize(responseSizes.size() * numRequests);

        for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
            final StreamingOutputCallResponse response = recorder.getValues().get(ix);
            assertThat(response.getPayload().getType()).isEqualTo(COMPRESSABLE);
            final int length = response.getPayload().getBody().size();
            final int expectedSize = responseSizes.get(ix % responseSizes.size());
            assertThat(length).withFailMessage("comparison failed at index " + ix)
                              .isEqualTo(expectedSize);
        }

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(requests.get(0));
            assertThat(rpcRes.get()).isEqualTo(recorder.getValues().get(0));
        });
    }

    @Test
    void serverStreamingShouldBeFlowControlled() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder().setSize(100000))
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder().setSize(100001))
                                          .build();
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[100000])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[100001])))
                                           .build());

        final long start = System.nanoTime();

        final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(10);
        final ClientCall<StreamingOutputCallRequest, StreamingOutputCallResponse> call =
                asyncStub.getChannel().newCall(TestServiceGrpc.getStreamingOutputCallMethod(),
                                               CallOptions.DEFAULT);
        call.start(new ClientCall.Listener<StreamingOutputCallResponse>() {
            @Override
            public void onHeaders(Metadata headers) {}

            @Override
            public void onMessage(final StreamingOutputCallResponse message) {
                queue.add(message);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                queue.add(status);
            }
        }, new Metadata());
        call.sendMessage(request);
        call.halfClose();

        // Time how long it takes to get the first response.
        call.request(1);

        final Object actualResponse1 = queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(actualResponse1).withFailMessage("Unexpected response: %s", actualResponse1)
                                   .isEqualTo(goldenResponses.get(0));
        final long firstCallDuration = System.nanoTime() - start;

        // Without giving additional flow control, make sure that we don't get another response. We wait
        // until we are comfortable the next message isn't coming. We may have very low nanoTime
        // resolution (like on Windows) or be using a testing, in-process transport where message
        // handling is instantaneous. In both cases, firstCallDuration may be 0, so round up sleep time
        // to at least 1ms.
        assertThat(queue.poll(Math.max(firstCallDuration * 4, 1_000_000), TimeUnit.NANOSECONDS)).isNull();

        // Make sure that everything still completes.
        call.request(1);
        final Object actualResponse2 = queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(actualResponse2).withFailMessage("Unexpected response: %s", actualResponse2)
                                   .isEqualTo(goldenResponses.get(1));
        call.request(1);
        assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Status.OK);
        call.cancel("Cancelled after all of the requests are done", null);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(rpcRes.get()).isEqualTo(goldenResponses.get(0));
        });
    }

    @Test
    void veryLargeRequest() throws Exception {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setType(COMPRESSABLE)
                                                .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
                             .setResponseSize(10)
                             .setResponseType(COMPRESSABLE)
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[10])))
                              .build();
        assertThat(blockingStub.unaryCall(request)).isEqualTo(goldenResponse);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(rpcRes.get()).isEqualTo(goldenResponse);
        });
    }

    @Test
    void veryLargeResponse() throws Exception {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(unaryPayloadLength())
                             .setResponseType(COMPRESSABLE)
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
                              .build();
        assertThat(blockingStub.unaryCall(request)).isEqualTo(goldenResponse);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(rpcRes.get()).isEqualTo(goldenResponse);
        });
    }

    @Test
    void exchangeHeadersUnaryCall_armeriaHeaders() throws Exception {
        TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOptions.HEADERS.newValue(
                                HttpHeaders.of(TestServiceImpl.EXTRA_HEADER_NAME, "dog")));

        final AtomicReference<Metadata> headers = new AtomicReference<>();
        final AtomicReference<Metadata> trailers = new AtomicReference<>();
        stub = stub.withInterceptors(MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));

        assertThat(stub.emptyCall(EMPTY)).isNotNull();

        // Assert that our side channel object is echoed back in both headers and trailers
        assertThat(CLIENT_HEADERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
        assertThat(SERVER_TRAILERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");

        assertThat(headers.get().keys()).contains(HttpHeaderNames.CONTENT_TYPE.toString(),
                                                  GrpcHeaderNames.GRPC_ENCODING.toString(),
                                                  GrpcHeaderNames.GRPC_ACCEPT_ENCODING.toString());
        assertThat(trailers.get().get(TestServiceImpl.EXTRA_HEADER_KEY)).isEqualTo("dog");
        assertThat(trailers.get().getAll(TestServiceImpl.STRING_VALUE_KEY)).containsExactly(
                StringValue.newBuilder().setValue("hello").build(),
                StringValue.newBuilder().setValue("world").build());

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(EMPTY);
            assertThat(rpcRes.get()).isEqualTo(EMPTY);
        });
    }

    @Test
    void exchangeHeadersUnaryCall_grpcMetadata() throws Exception {
        final Metadata metadata = new Metadata();
        metadata.put(TestServiceImpl.EXTRA_HEADER_KEY, "dog");

        TestServiceBlockingStub stub =
                blockingStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));

        final AtomicReference<Metadata> headers = new AtomicReference<>();
        final AtomicReference<Metadata> trailers = new AtomicReference<>();
        stub = stub.withInterceptors(MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));

        assertThat(stub.emptyCall(EMPTY)).isNotNull();

        final HttpHeaders clientHeaders = CLIENT_HEADERS_CAPTURE.get();
        assertThat(clientHeaders.get(HttpHeaderNames.TE))
                .isEqualTo(HttpHeaderValues.TRAILERS.toString());

        // Assert that our side channel object is echoed back in both headers and trailers
        assertThat(clientHeaders.get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
        assertThat(SERVER_TRAILERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");

        assertThat(headers.get().keys()).contains(HttpHeaderNames.CONTENT_TYPE.toString(),
                                                  GrpcHeaderNames.GRPC_ENCODING.toString(),
                                                  GrpcHeaderNames.GRPC_ACCEPT_ENCODING.toString());
        assertThat(trailers.get().get(TestServiceImpl.EXTRA_HEADER_KEY)).isEqualTo("dog");
        assertThat(trailers.get().getAll(TestServiceImpl.STRING_VALUE_KEY)).containsExactly(
                StringValue.newBuilder().setValue("hello").build(),
                StringValue.newBuilder().setValue("world").build());

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(EMPTY);
            assertThat(rpcRes.get()).isEqualTo(EMPTY);
        });
    }

    @Test
    void credentialsUnaryCall() {
        final TestServiceBlockingStub stub =
                // Explicitly construct URL to better test authority.
                GrpcClients.builder("gproto+http://localhost:" + server.httpPort())
                           .build(TestServiceBlockingStub.class)
                           .withCallCredentials(
                                   new CallCredentials() {
                                       @Override
                                       public void applyRequestMetadata(RequestInfo requestInfo,
                                                                        Executor appExecutor,
                                                                        MetadataApplier applier) {
                                           assertThat(requestInfo.getMethodDescriptor())
                                                   .isEqualTo(TestServiceGrpc.getEmptyCallMethod());
                                           assertThat(requestInfo.getAuthority())
                                                   .isEqualTo("localhost:" + server.httpPort());
                                           assertThat(requestInfo.getSecurityLevel())
                                                   .isEqualTo(SecurityLevel.NONE);
                                           assertThat(appExecutor).isEqualTo(
                                                   CommonPools.blockingTaskExecutor());

                                           CommonPools.blockingTaskExecutor().schedule(() -> {
                                               final Metadata metadata = new Metadata();
                                               metadata.put(TestServiceImpl.EXTRA_HEADER_KEY, "token");
                                               applier.apply(metadata);
                                           }, 100, TimeUnit.MILLISECONDS);
                                       }

                                       @Override
                                       public void thisUsesUnstableApi() {
                                       }
                                   });

        assertThat(stub.emptyCall(EMPTY)).isNotNull();

        final HttpHeaders clientHeaders = CLIENT_HEADERS_CAPTURE.get();
        assertThat(clientHeaders.get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("token");
    }

    @Test
    void credentialsUnaryCall_withBuilder() {
        final TestServiceBlockingStub stub =
                // Explicitly construct URL to better test authority.
                GrpcClients.builder("gproto+http://localhost:" + server.httpPort())
                           .callCredentials(new CallCredentials() {
                               @Override
                               public void applyRequestMetadata(RequestInfo requestInfo,
                                                                Executor appExecutor,
                                                                MetadataApplier applier) {
                                   assertThat(requestInfo.getMethodDescriptor())
                                           .isEqualTo(TestServiceGrpc.getEmptyCallMethod());
                                   assertThat(requestInfo.getAuthority())
                                           .isEqualTo("localhost:" + server.httpPort());
                                   assertThat(requestInfo.getSecurityLevel())
                                           .isEqualTo(SecurityLevel.NONE);
                                   assertThat(appExecutor).isEqualTo(
                                           CommonPools.blockingTaskExecutor());

                                   CommonPools.blockingTaskExecutor().schedule(() -> {
                                       final Metadata metadata = new Metadata();
                                       metadata.put(TestServiceImpl.EXTRA_HEADER_KEY, "token");
                                       applier.apply(metadata);
                                   }, 100, TimeUnit.MILLISECONDS);
                               }

                               @Override
                               public void thisUsesUnstableApi() {}
                           })
                           .build(TestServiceBlockingStub.class);

        assertThat(stub.emptyCall(EMPTY)).isNotNull();

        final HttpHeaders clientHeaders = CLIENT_HEADERS_CAPTURE.get();
        assertThat(clientHeaders.get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("token");
        CLIENT_HEADERS_CAPTURE.set(null);

        // Override client level CallCredentials with CallOptions.
        final TestServiceBlockingStub stubWithCredentials =
                stub.withCallCredentials(new CallCredentials() {
                    @Override
                    public void applyRequestMetadata(RequestInfo requestInfo,
                                                     Executor appExecutor,
                                                     MetadataApplier applier) {
                        final Metadata metadata = new Metadata();
                        metadata.put(TestServiceImpl.EXTRA_HEADER_KEY, "token2");
                        applier.apply(metadata);
                    }

                    @Override
                    public void thisUsesUnstableApi() {}
                });

        assertThat(stubWithCredentials.emptyCall(EMPTY)).isNotNull();

        final HttpHeaders clientHeaders0 = CLIENT_HEADERS_CAPTURE.get();
        assertThat(clientHeaders0.get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("token2");
    }

    @Test
    void credentialsUnaryCall_https() {
        final TestServiceBlockingStub stub =
                // Explicitly construct URL to better test authority.
                GrpcClients.builder("gproto+https://127.0.0.1:" + server.httpsPort())
                           .factory(ClientFactory.insecure())
                           .build(TestServiceBlockingStub.class)
                           .withCallCredentials(
                                   new CallCredentials() {
                                       @Override
                                       public void applyRequestMetadata(RequestInfo requestInfo,
                                                                        Executor appExecutor,
                                                                        MetadataApplier applier) {
                                           assertThat(requestInfo.getAuthority())
                                                   .isEqualTo("127.0.0.1:" + server.httpsPort());
                                           assertThat(requestInfo.getSecurityLevel())
                                                   .isEqualTo(SecurityLevel.PRIVACY_AND_INTEGRITY);
                                           applier.apply(new Metadata());
                                       }

                                       @Override
                                       public void thisUsesUnstableApi() {
                                       }
                                   });

        assertThat(stub.emptyCall(EMPTY)).isNotNull();
    }

    @Test
    void credentialsUnaryCall_fail() {
        final TestServiceBlockingStub stub =
                // Explicitly construct URL to better test authority.
                GrpcClients.builder("gproto+https://127.0.0.1:" + server.httpsPort())
                           .factory(ClientFactory.insecure())
                           .build(TestServiceBlockingStub.class)
                           .withCallCredentials(
                                   new CallCredentials() {
                                       @Override
                                       public void applyRequestMetadata(RequestInfo requestInfo,
                                                                        Executor appExecutor,
                                                                        MetadataApplier applier) {
                                           applier.fail(Status.FAILED_PRECONDITION);
                                       }

                                       @Override
                                       public void thisUsesUnstableApi() {
                                       }
                                   });

        assertThatThrownBy(() -> stub.emptyCall(EMPTY))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                                        t -> assertThat(t.getStatus().getCode())
                                                .isEqualTo(Code.FAILED_PRECONDITION));
    }

    @Test
    void credentialsStreamingCall() throws Exception {
        final List<StreamingInputCallRequest> requests = Arrays.asList(
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[27182])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[8])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[1828])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[45904])))
                                         .build());
        final StreamingInputCallResponse goldenResponse =
                StreamingInputCallResponse.newBuilder()
                                          .setAggregatedPayloadSize(74922)
                                          .build();

        final CompletableFuture<Void> finishCredentials = new CompletableFuture<>();
        final TestServiceStub stub = asyncStub.withCallCredentials(new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor,
                                             MetadataApplier applier) {
                final Metadata metadata = new Metadata();
                metadata.put(TestServiceImpl.EXTRA_HEADER_KEY, "token");
                finishCredentials.thenAccept(unused -> applier.apply(metadata));
            }

            @Override
            public void thisUsesUnstableApi() {
            }
        });

        final StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingInputCallRequest> requestObserver =
                stub.streamingInputCall(responseObserver);

        // Make sure request messages are buffered while authentication is happening.
        for (StreamingInputCallRequest request : requests) {
            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();

        assertThat(responseObserver.firstValue().isDone()).isFalse();
        finishCredentials.complete(null);
        assertThat(responseObserver.firstValue().get()).isEqualTo(goldenResponse);
        responseObserver.awaitCompletion();

        final HttpHeaders clientHeaders = CLIENT_HEADERS_CAPTURE.get();
        assertThat(clientHeaders.get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("token");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(requests.get(0));
            assertThat(rpcRes.get()).isEqualTo(goldenResponse);
        });
    }

    @Test
    void exchangeHeadersStreamingCall() throws Exception {
        final TestServiceStub stub =
                Clients.newDerivedClient(
                        asyncStub,
                        ClientOptions.HEADERS.newValue(
                                HttpHeaders.of(TestServiceImpl.EXTRA_HEADER_NAME, "dog")));

        final List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
        final StreamingOutputCallRequest.Builder streamingOutputBuilder =
                StreamingOutputCallRequest.newBuilder();
        streamingOutputBuilder.setResponseType(COMPRESSABLE);
        for (Integer size : responseSizes) {
            streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
        }
        final StreamingOutputCallRequest request = streamingOutputBuilder.build();

        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestStream =
                stub.fullDuplexCall(recorder);

        final int numRequests = 10;
        final List<StreamingOutputCallRequest> requests = new ArrayList<>(numRequests);

        for (int ix = numRequests; ix > 0; --ix) {
            requests.add(request);
            requestStream.onNext(request);
        }
        requestStream.onCompleted();
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).hasSize(responseSizes.size() * numRequests);

        final HttpHeaders clientHeaders = CLIENT_HEADERS_CAPTURE.get();
        assertThat(clientHeaders.get(HttpHeaderNames.TE))
                .isEqualTo(HttpHeaderValues.TRAILERS.toString());

        // Assert that our side channel object is echoed back in both headers and trailers
        assertThat(clientHeaders.get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
        assertThat(SERVER_TRAILERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(requests.get(0));
            assertThat(rpcRes.get()).isEqualTo(recorder.getValues().get(0));
        });
    }

    @Test
    void sendsTimeoutHeader() {
        final long configuredTimeoutMinutes = 100;
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(
                                TimeUnit.MINUTES.toMillis(configuredTimeoutMinutes)));
        stub.emptyCall(EMPTY);
        final long transferredTimeoutMinutes = TimeUnit.NANOSECONDS.toMinutes(
                TimeoutHeaderUtil.fromHeaderValue(
                        CLIENT_HEADERS_CAPTURE.get().get(GrpcHeaderNames.GRPC_TIMEOUT)));
        assertThat(transferredTimeoutMinutes).isEqualTo(configuredTimeoutMinutes);
    }

    @Test
    void deadlineNotExceeded() {
        // warm up the channel and JVM
        blockingStub.emptyCall(Empty.getDefaultInstance());
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(
                                TimeUnit.SECONDS.toMillis(10)));
        stub
                .streamingOutputCall(
                        StreamingOutputCallRequest.newBuilder()
                                                  .addResponseParameters(
                                                          ResponseParameters.newBuilder()
                                                                            .setIntervalUs(0))
                                                  .build())
                .next();
    }

    @Test
    void deadlineExceeded() throws Exception {
        // warm up the channel and JVM
        blockingStub.emptyCall(Empty.getDefaultInstance());
        requestLogQueue.take();

        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(10L));
        final StreamingOutputCallRequest request = StreamingOutputCallRequest
                .newBuilder()
                .addResponseParameters(ResponseParameters.newBuilder()
                                                         .setIntervalUs((int) TimeUnit.SECONDS.toMicros(10)))
                .build();

        final Throwable t = catchThrowable(() -> stub.streamingOutputCall(request).next());
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.DEADLINE_EXCEEDED.getCode());

        checkRequestLogError((headers, rpcReq, cause) -> {
            assertThat(rpcReq).isNotNull();
            assertThat(rpcReq.params()).containsExactly(request);
            assertResponseTimeoutExceptionOrDeadlineExceeded(cause);
        });
    }

    @Test
    void deadlineExceededServerStreaming() throws Exception {
        // warm up the channel and JVM
        blockingStub.emptyCall(Empty.getDefaultInstance());
        requestLogQueue.take();

        final ResponseParameters.Builder responseParameters = ResponseParameters.newBuilder()
                                                                                .setSize(1)
                                                                                .setIntervalUs(20000);
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(responseParameters)
                                          .addResponseParameters(responseParameters)
                                          .addResponseParameters(responseParameters)
                                          .addResponseParameters(responseParameters)
                                          .build();
        final StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        final TestServiceStub stub =
                Clients.newDerivedClient(
                        asyncStub,
                        ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(30L));
        stub.streamingOutputCall(request, recorder);
        recorder.awaitCompletion();

        assertThat(recorder.getError()).isNotNull();
        assertThat(GrpcExceptionHandlerFunction.of()
                                               .apply(null, recorder.getError(), null)
                                               .getCode())
                .isEqualTo(Status.DEADLINE_EXCEEDED.getCode());

        checkRequestLogError((headers, rpcReq, cause) -> {
            assertThat(rpcReq).isNotNull();
            assertThat(rpcReq.params()).containsExactly(request);
            assertResponseTimeoutExceptionOrDeadlineExceeded(cause);
        });
    }

    private static void assertResponseTimeoutExceptionOrDeadlineExceeded(@Nullable Throwable cause) {
        assertThat(cause).isNotNull();

        // Both exceptions can be raised when a timeout occurs due to timing issues,
        // and the first triggered one wins.
        if (cause instanceof ResponseTimeoutException) {
            return;
        }
        if (cause instanceof StatusRuntimeException) {
            assertThat(((StatusRuntimeException) cause).getStatus().getCode())
                    .isEqualTo(Code.DEADLINE_EXCEEDED);
            return;
        }
        fail("cause must be a ResponseTimeoutException or a StatusRuntimeException: ", cause);
    }

    @Test
    void deadlineInPast() {
        // Test once with idle channel and once with active channel
        final TestServiceGrpc.TestServiceBlockingStub stub =
                blockingStub.withDeadlineAfter(-10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> stub.emptyCall(EMPTY))
                .isInstanceOfSatisfying(StatusRuntimeException.class, t -> {
                    assertThat(t.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
                    assertThat(t.getStatus().getDescription())
                            .startsWith("ClientCall started after deadline exceeded");
                });

        // warm up the channel
        blockingStub.emptyCall(Empty.getDefaultInstance());
        assertThatThrownBy(() -> stub.emptyCall(EMPTY))
                .isInstanceOfSatisfying(StatusRuntimeException.class, t -> {
                    assertThat(t.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
                    assertThat(t.getStatus().getDescription())
                            .startsWith("ClientCall started after deadline exceeded");
                });
    }

    @Test
    void deadlineInFuture() throws Exception {
        final TestServiceGrpc.TestServiceStub stub =
                asyncStub.withDeadlineAfter(500, TimeUnit.MILLISECONDS);
        final StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        stub.streamingOutputCall(
                StreamingOutputCallRequest
                        .newBuilder()
                        .addResponseParameters(
                                ResponseParameters.newBuilder()
                                                  .setIntervalUs((int) TimeUnit.SECONDS.toMicros(1))
                                                  .build())
                        .build(),
                responseObserver);
        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);

        await().untilAsserted(() -> assertThat(CLIENT_HEADERS_CAPTURE.get()).isNotNull());

        final String grpcTimeout = CLIENT_HEADERS_CAPTURE.get().get(GrpcHeaderNames.GRPC_TIMEOUT);
        // grpc-timeout header value is computed when the request has started processing and will be lower
        // than the 500ms specified in the stub factory above since some time will have passed. Our default is
        // 10s though so this should still verify the header was computed correctly.
        assertThat(TimeoutHeaderUtil.fromHeaderValue(grpcTimeout))
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(500));

        assertThat(responseObserver.getError()).isInstanceOfSatisfying(
                StatusRuntimeException.class, t -> {
                    assertThat(t.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
                    assertThat(t.getStatus().getDescription()).contains("deadline exceeded after");
                });
    }

    @Test
    void maxInboundSize_exact() {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        final int size = blockingStub.streamingOutputCall(request).next().getSerializedSize();

        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES.newValue(size));
        stub.streamingOutputCall(request).next();
    }

    @Test
    void maxInboundSize_tooBig() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        final Iterator<StreamingOutputCallResponse> response = blockingStub.streamingOutputCall(request);
        final int size = response.next().getSerializedSize();
        // Need to drain the inbound stream to ensure request-log is available.
        assertThat(response.hasNext()).isFalse();

        requestLogQueue.take();

        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES.newValue(size - 1));
        final Throwable t = catchThrowable(() -> stub.streamingOutputCall(request).next());
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
        assertThat(Exceptions.traceText(t)).contains("exceeds maximum");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
        });
    }

    @Test
    void maxOutboundSize_exact() {
        // set at least one field to ensure the size is non-zero.
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES.newValue(
                                request.getSerializedSize()));
        stub.streamingOutputCall(request).next();
    }

    @Test
    void maxOutboundSize_tooBig() throws Exception {
        // set at least one field to ensure the size is non-zero.
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        final TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES.newValue(
                                request.getSerializedSize() - 1));
        final Throwable t = catchThrowable(() -> stub.streamingOutputCall(request).next());
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode()).isEqualTo(Code.CANCELLED);
        assertThat(Exceptions.traceText(t)).contains("message too large");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.CANCELLED);
        });
    }

    @Test
    void statusCodeAndMessage() throws Exception {
        final int errorCode = 2;
        final String errorMessage = "test status message";
        final EchoStatus responseStatus = EchoStatus.newBuilder()
                                                    .setCode(errorCode)
                                                    .setMessage(errorMessage)
                                                    .build();
        final SimpleRequest simpleRequest = SimpleRequest.newBuilder()
                                                         .setResponseStatus(responseStatus)
                                                         .build();
        final StreamingOutputCallRequest streamingRequest =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseStatus(responseStatus)
                                          .build();

        // Test UnaryCall
        final Throwable t = catchThrowable(() -> blockingStub.unaryCall(simpleRequest));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        final StatusRuntimeException e = (StatusRuntimeException) t;
        assertThat(e.getStatus().getCode()).isEqualTo(Status.UNKNOWN.getCode());
        assertThat(e.getStatus().getDescription()).isEqualTo(errorMessage);

        // Test FullDuplexCall
        @SuppressWarnings("unchecked")
        final StreamObserver<StreamingOutputCallResponse> responseObserver =
                mock(StreamObserver.class);
        final StreamObserver<StreamingOutputCallRequest> requestObserver =
                asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onNext(streamingRequest);
        requestObserver.onCompleted();

        final ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver,
               timeout(operationTimeoutMillis())).onError(captor.capture());
        assertThat(GrpcExceptionHandlerFunction.of()
                                               .apply(null, captor.getValue(), null)
                                               .getCode()).isEqualTo(Status.UNKNOWN.getCode());
        assertThat(GrpcExceptionHandlerFunction.of()
                                               .apply(null, captor.getValue(), null)
                                               .getDescription()).isEqualTo(errorMessage);
        verifyNoMoreInteractions(responseObserver);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.params()).containsExactly(simpleRequest);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.UNKNOWN);
            assertThat(grpcStatus.getDescription()).isEqualTo(errorMessage);
        });
    }

    @Test
    void errorNoMessage() throws Exception {
        final Throwable t = catchThrowable(() -> unitTestBlockingStub.errorNoMessage(REQUEST_MESSAGE));
        assertThat(t).isInstanceOfSatisfying(StatusRuntimeException.class, ex -> {
            assertThat(ex.getStatus()).isNotNull();
            assertThat(ex.getStatus().getCode()).isEqualTo(Code.ABORTED);
            assertThat(ex.getStatus().getDescription()).isNull();
        });

        checkRequestLogError((headers, rpcReq, cause) -> {
            assertThat(rpcReq).isNotNull();
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("ErrorNoMessage");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(cause).isInstanceOfSatisfying(StatusRuntimeException.class, ex -> {
                assertThat(ex.getStatus()).isNotNull();
                assertThat(ex.getStatus().getCode()).isEqualTo(Code.ABORTED);
                assertThat(ex.getStatus().getDescription()).isNull();
            });
        });
    }

    @Test
    void errorWithMessage() throws Exception {
        final BiConsumer<Status, Metadata> checkException = (status, meta) -> {
            assertThat(status).isNotNull();
            assertThat(status.getCode()).isEqualTo(Code.DATA_LOSS);
            assertThat(status.getDescription()).isEqualTo("data loss!");
            assertThat(status.getCause()).isNull();
            assertThat(meta).isNotNull();
            assertThat(meta.getAll(INT_32_VALUE_KEY))
                    .containsExactly(Int32Value.newBuilder().setValue(1).build());
            assertThat(meta.get(CUSTOM_VALUE_KEY)).isEqualTo("string");
        };
        final Throwable t = catchThrowable(() -> unitTestBlockingStub.errorWithMessage(REQUEST_MESSAGE));
        assertThat(t).isInstanceOfSatisfying(StatusRuntimeException.class,
                                             ex -> checkException.accept(ex.getStatus(), ex.getTrailers()));
        checkRequestLogError((headers, rpcReq, cause) -> {
            assertThat(rpcReq).isNotNull();
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("ErrorWithMessage");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(cause).isInstanceOfSatisfying(
                    StatusRuntimeException.class, ex -> checkException.accept(ex.getStatus(),
                                                                              ex.getTrailers()));
        });
    }

    @Test
    void errorFromClientInStream() throws Exception {
        final BiConsumer<Status, Metadata> checkException = (status, meta) -> {
            // the cause is wrapped by CANCELLED
            assertThat(status).isNotNull();
            assertThat(status.getCode()).isEqualTo(Code.CANCELLED);
            assertThat(meta).isNotNull();
            assertThat(meta.keys()).isEmpty();
            // but the status holds original cause
            assertThat(status.getCause()).isInstanceOfSatisfying(StatusRuntimeException.class, ex -> {
                assertThat(ex.getStatus()).isNotNull();
                assertThat(ex.getStatus().getCode()).isEqualTo(Code.DATA_LOSS);
                assertThat(ex.getStatus().getDescription()).isEqualTo("data loss!");
                assertThat(ex.getStatus().getCause()).isNull();
                assertThat(ex.getTrailers()).isNotNull();
                assertThat(ex.getTrailers().getAll(INT_32_VALUE_KEY))
                        .containsExactly(Int32Value.newBuilder().setValue(1).build());
                assertThat(ex.getTrailers().get(CUSTOM_VALUE_KEY)).isEqualTo("string");
            });
        };
        final StreamRecorder<SimpleResponse> response = StreamRecorder.create();
        final StreamObserver<SimpleRequest> request = unitTestAsyncStub.errorFromClient(response);
        // Sending request and receiving response is required to ensure connection before calling onError.
        request.onNext(REQUEST_MESSAGE);
        response.firstValue().get();
        request.onError(UnitTestServiceImpl.buildExceptionWithMeta());
        response.awaitCompletion();
        assertThat(response.getError()).isInstanceOfSatisfying(
                StatusRuntimeException.class, ex -> checkException.accept(ex.getStatus(), ex.getTrailers()));

        checkRequestLogError((headers, rpcReq, cause) -> {
            assertThat(rpcReq).isNotNull();
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("ErrorFromClient");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(cause).isInstanceOfSatisfying(
                    StatusRuntimeException.class, ex -> checkException.accept(ex.getStatus(),
                                                                              ex.getTrailers()));
        });
    }

    /** Sends an rpc to an unimplemented method within TestService. */
    @Test
    void unimplementedMethod() throws Exception {
        final Throwable t = catchThrowable(() -> blockingStub.unimplementedCall(Empty.getDefaultInstance()));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.UNIMPLEMENTED.getCode());

        checkRequestLogError((headers, rpcReq, cause) -> {
            assertThat(rpcReq).isNotNull();
            assertThat(rpcReq.params()).containsExactly(Empty.getDefaultInstance());
            assertThat(headers.get(GrpcHeaderNames.GRPC_STATUS)).isEqualTo(
                    String.valueOf(Status.UNIMPLEMENTED.getCode().value()));
        });
    }

    @Test
    void unimplementedService() throws Exception {
        final UnimplementedServiceGrpc.UnimplementedServiceBlockingStub stub =
                UnimplementedServiceGrpc.newBlockingStub(asyncStub.getChannel());
        final Throwable t = catchThrowable(() -> stub.unimplementedCall(Empty.getDefaultInstance()));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.UNIMPLEMENTED.getCode());

        checkRequestLogError((headers, rpcReq, cause) -> {
            // rpcReq may be null depending on timing.
            if (rpcReq != null) {
                assertThat(rpcReq.params()).containsExactly(Empty.getDefaultInstance());
            }
        });
    }

    /** Start a fullDuplexCall which the server will not respond, and verify the deadline expires. */
    @Test
    void timeoutOnSleepingServer() throws Exception {
        final TestServiceStub stub = Clients.newDerivedClient(
                asyncStub,
                ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(1L));

        final StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingOutputCallRequest> requestObserver =
                stub.fullDuplexCall(responseObserver);

        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest
                        .newBuilder()
                        .setPayload(Payload.newBuilder()
                                           .setBody(ByteString.copyFrom(new byte[27182])))
                        .addResponseParameters(
                                ResponseParameters.newBuilder()
                                                  .setIntervalUs((int) TimeUnit.SECONDS.toMicros(10)))
                        .build();
        try {
            requestObserver.onNext(request);
            // TODO(anuraag): Upstream test does not need to call onCompleted - response timeout is set at the
            // start of the stream, which seems to make sense. Figure out how to fix this timeout handling in
            // armeria.
            requestObserver.onCompleted();
        } catch (IllegalStateException expected) {
            // This can happen if the stream has already been terminated due to deadline exceeded.
        }

        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(responseObserver.getValues()).isEmpty();
        assertThat(responseObserver.getError()).isNotNull();
        // TODO(anuraag): As gRPC supports handling timeouts in the server or client due to the grpc-timeout
        // header, it's not guaranteed which is the source of this error.
        // Until https://github.com/line/armeria/issues/521 a server side timeout will not have the correct
        // status so we don't verify it for now.
        //assertThat(GrpcStatus.fromThrowable(responseObserver.getError()).getCode())
        //.isEqualTo(Status.DEADLINE_EXCEEDED.getCode());
    }

    @Test
    void nonAsciiStatusMessage() {
        final String statusMessage = "ほげほげ";
        assertThatThrownBy(() -> blockingStub.unaryCall(
                SimpleRequest.newBuilder()
                             .setResponseStatus(EchoStatus.newBuilder()
                                                          .setCode(2)
                                                          .setMessage(statusMessage))
                             .build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                                        e -> assertThat(e.getStatus().getDescription())
                                                .isEqualTo(statusMessage));
    }

    @Test
    void endpointRemapper() {
        final EndpointGroup group = Endpoint.of("127.0.0.1", server.httpPort());
        final TestServiceBlockingStub stub =
                GrpcClients.builder("http://my-group")
                           .endpointRemapper(endpoint -> {
                               if ("my-group".equals(endpoint.host())) {
                                   return group;
                               } else {
                                   return endpoint;
                               }
                           })
                           .build(TestServiceBlockingStub.class);

        assertThat(stub.emptyCall(Empty.newBuilder().build())).isNotNull();
    }

    private static void assertSuccess(StreamRecorder<?> recorder) {
        if (recorder.getError() != null) {
            throw new AssertionError(recorder.getError());
        }
    }

    private static int unaryPayloadLength() {
        // 10MiB.
        return 10485760;
    }

    private static int operationTimeoutMillis() {
        return 5000;
    }

    private void checkRequestLog(RequestLogChecker checker) throws Exception {
        final RequestLog log = requestLogQueue.take();
        assertThat(log.isComplete()).isTrue();

        final RpcRequest rpcReq = (RpcRequest) log.requestContent();
        final RpcResponse rpcRes = (RpcResponse) log.responseContent();
        assertThat(rpcReq).isNotNull();
        assertThat((Object) rpcRes).isNotNull();
        assertThat(rpcReq.serviceType()).isEqualTo(GrpcLogUtil.class);

        final Status grpcStatus;
        if (rpcRes.cause() != null) {
            grpcStatus = ((StatusRuntimeException) rpcRes.cause()).getStatus();
        } else {
            grpcStatus = null;
        }

        checker.check(rpcReq, rpcRes, grpcStatus);
    }

    private void checkRequestLogError(RequestLogErrorChecker checker) throws Exception {
        final RequestLog log = requestLogQueue.take();
        assertThat(log.isComplete()).isTrue();

        final RpcRequest rpcReq = (RpcRequest) log.requestContent();
        if (rpcReq != null) {
            assertThat(rpcReq.serviceType()).isEqualTo(GrpcLogUtil.class);
        }

        checker.check(log.responseHeaders(), rpcReq, log.responseCause());
    }

    @FunctionalInterface
    private interface RequestLogChecker {
        void check(RpcRequest rpcReq, RpcResponse rpcRes, @Nullable Status grpcStatus) throws Exception;
    }

    @FunctionalInterface
    private interface RequestLogErrorChecker {
        void check(HttpHeaders headers, @Nullable RpcRequest rpcReq,
                   @Nullable Throwable cause) throws Exception;
    }
}
