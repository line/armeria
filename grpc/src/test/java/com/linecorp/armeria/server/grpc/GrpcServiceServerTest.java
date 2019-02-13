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

package com.linecorp.armeria.server.grpc;

import static com.linecorp.armeria.internal.grpc.GrpcTestUtil.REQUEST_MESSAGE;
import static com.linecorp.armeria.internal.grpc.GrpcTestUtil.RESPONSE_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

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
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.grpc.testing.Messages.EchoStatus;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallRequest;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc.UnitTestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc.UnitTestServiceImplBase;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc.UnitTestServiceStub;
import com.linecorp.armeria.internal.PathAndQuery;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.grpc.GrpcTestUtil;
import com.linecorp.armeria.internal.grpc.StreamRecorder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.grpc.Codec;
import io.grpc.DecompressorRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.netty.util.AsciiString;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

public class GrpcServiceServerTest {

    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    private static AsciiString LARGE_PAYLOAD;

    @BeforeClass
    public static void createLargePayload() {
        LARGE_PAYLOAD = AsciiString.of(Strings.repeat("a", MAX_MESSAGE_SIZE + 1));
    }

    @AfterClass
    public static void destroyLargePayload() {
        // Dereference to reduce the memory pressure on the VM.
        LARGE_PAYLOAD = null;
    }

    // Used to communicate completion to a test when it is not possible to return to the client.
    private static final AtomicReference<Boolean> COMPLETED = new AtomicReference<>();

    // Used to communicate that the client has closed to allow the server to continue executing logic when
    // required.
    private static final AtomicReference<Boolean> CLIENT_CLOSED = new AtomicReference<>();

    private static class UnitTestServiceImpl extends UnitTestServiceImplBase {

        private static final AttributeKey<Integer> CHECK_REQUEST_CONTEXT_COUNT =
                AttributeKey.valueOf(UnitTestServiceImpl.class, "CHECK_REQUEST_CONTEXT_COUNT");

        @Override
        public void staticUnaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            if (!request.equals(REQUEST_MESSAGE)) {
                responseObserver.onError(new IllegalArgumentException("Unexpected request: " + request));
                return;
            }
            responseObserver.onNext(RESPONSE_MESSAGE);
            responseObserver.onCompleted();
        }

        @Override
        public void staticStreamedOutputCall(SimpleRequest request,
                                             StreamObserver<SimpleResponse> responseObserver) {
            if (!request.equals(REQUEST_MESSAGE)) {
                responseObserver.onError(new IllegalArgumentException("Unexpected request: " + request));
                return;
            }
            responseObserver.onNext(RESPONSE_MESSAGE);
            responseObserver.onNext(RESPONSE_MESSAGE);
            responseObserver.onCompleted();
        }

        @Override
        public void errorNoMessage(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(Status.ABORTED.asException());
        }

        @Override
        public void errorWithMessage(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(Status.ABORTED.withDescription("aborted call").asException());
        }

        @Override
        public void unaryThrowsError(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            throw Status.ABORTED.withDescription("call aborted").asRuntimeException();
        }

        @Override
        public StreamObserver<SimpleRequest> streamThrowsError(
                StreamObserver<SimpleResponse> responseObserver) {
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    throw Status.ABORTED.withDescription("bad streaming message").asRuntimeException();
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };
        }

        @Override
        public StreamObserver<SimpleRequest> streamThrowsErrorInStub(
                StreamObserver<SimpleResponse> responseObserver) {
            throw Status.ABORTED.withDescription("bad streaming stub").asRuntimeException();
        }

        @Override
        public void staticUnaryCallSetsMessageCompression(SimpleRequest request,
                                                          StreamObserver<SimpleResponse> responseObserver) {
            if (!request.equals(REQUEST_MESSAGE)) {
                responseObserver.onError(new IllegalArgumentException("Unexpected request: " + request));
                return;
            }
            final ServerCallStreamObserver<SimpleResponse> callObserver =
                    (ServerCallStreamObserver<SimpleResponse>) responseObserver;
            callObserver.setCompression("gzip");
            callObserver.setMessageCompression(true);
            responseObserver.onNext(RESPONSE_MESSAGE);
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<SimpleRequest> checkRequestContext(
                StreamObserver<SimpleResponse> responseObserver) {
            final RequestContext ctx = RequestContext.current();
            ctx.attr(CHECK_REQUEST_CONTEXT_COUNT).set(0);
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    final RequestContext ctx = RequestContext.current();
                    final Attribute<Integer> attr = ctx.attr(CHECK_REQUEST_CONTEXT_COUNT);
                    attr.set(attr.get() + 1);
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {
                    final RequestContext ctx = RequestContext.current();
                    final int count = ctx.attr(CHECK_REQUEST_CONTEXT_COUNT).get();
                    responseObserver.onNext(
                            SimpleResponse.newBuilder()
                                          .setPayload(
                                                  Payload.newBuilder()
                                                         .setBody(
                                                                 ByteString.copyFromUtf8(
                                                                         Integer.toString(count))))
                                          .build());
                    responseObserver.onCompleted();
                }
            };
        }

        @Override
        public StreamObserver<SimpleRequest> streamClientCancels(
                StreamObserver<SimpleResponse> responseObserver) {
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    responseObserver.onNext(SimpleResponse.getDefaultInstance());
                }

                @Override
                public void onError(Throwable t) {
                    COMPLETED.set(true);
                }

                @Override
                public void onCompleted() {}
            };
        }

        @Override
        public void streamClientCancelsBeforeResponseClosedCancels(
                SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            ((ServerCallStreamObserver<?>) responseObserver).setOnCancelHandler(() -> COMPLETED.set(true));
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
        }
    }

    private static final BlockingQueue<RequestLog> requestLogQueue = new LinkedTransferQueue<>();

    @ClassRule
    public static ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(EventLoopGroups.newEventLoopGroup(1), true);
            sb.defaultMaxRequestLength(0);

            sb.serviceUnder("/", new GrpcServiceBuilder()
                    .setMaxInboundMessageSizeBytes(MAX_MESSAGE_SIZE)
                    .addService(new UnitTestServiceImpl())
                    .enableUnframedRequests(true)
                    .supportedSerializationFormats(GrpcSerializationFormats.values())
                    .build()
                    .decorate(LoggingService.newDecorator())
                    .decorate((delegate, ctx, req) -> {
                        ctx.log().addListener(requestLogQueue::add, RequestLogAvailability.COMPLETE);
                        return delegate.serve(ctx, req);
                    }));
        }
    };

    @ClassRule
    public static ServerRule serverWithNoMaxMessageSize = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(EventLoopGroups.newEventLoopGroup(1), true);
            sb.defaultMaxRequestLength(0);

            sb.serviceUnder("/", new GrpcServiceBuilder()
                    .addService(new UnitTestServiceImpl())
                    .build()
                    .decorate(LoggingService.newDecorator())
                    .decorate((delegate, ctx, req) -> {
                        ctx.log().addListener(requestLogQueue::add, RequestLogAvailability.COMPLETE);
                        return delegate.serve(ctx, req);
                    }));
        }
    };

    @ClassRule
    public static ServerRule serverWithLongMaxRequestLimit = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(EventLoopGroups.newEventLoopGroup(1), true);
            sb.defaultMaxRequestLength(Long.MAX_VALUE);

            sb.serviceUnder("/", new GrpcServiceBuilder()
                    .addService(new UnitTestServiceImpl())
                    .build()
                    .decorate(LoggingService.newDecorator())
                    .decorate((delegate, ctx, req) -> {
                        ctx.log().addListener(requestLogQueue::add, RequestLogAvailability.COMPLETE);
                        return delegate.serve(ctx, req);
                    }));
        }
    };

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    private static ManagedChannel channel;

    private UnitTestServiceBlockingStub blockingClient;
    private UnitTestServiceStub streamingClient;

    @BeforeClass
    public static void setUpChannel() {
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                       .usePlaintext()
                                       .build();
    }

    @AfterClass
    public static void tearDownChannel() {
        channel.shutdownNow();
    }

    @Before
    public void setUp() {
        COMPLETED.set(false);
        CLIENT_CLOSED.set(false);
        blockingClient = UnitTestServiceGrpc.newBlockingStub(channel);
        streamingClient = UnitTestServiceGrpc.newStub(channel);

        PathAndQuery.clearCachedPaths();
        requestLogQueue.clear();
    }

    @Test
    public void unary_normal() throws Exception {
        assertThat(blockingClient.staticUnaryCall(REQUEST_MESSAGE)).isEqualTo(RESPONSE_MESSAGE);

        // Confirm gRPC paths are cached despite using serviceUnder
        assertThat(PathAndQuery.cachedPaths())
                .contains("/armeria.grpc.testing.UnitTestService/StaticUnaryCall");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    public void streamedOutput_normal() throws Exception {
        final StreamRecorder<SimpleResponse> recorder = StreamRecorder.create();
        streamingClient.staticStreamedOutputCall(REQUEST_MESSAGE, recorder);
        recorder.awaitCompletion();
        assertThat(recorder.getValues()).containsExactly(RESPONSE_MESSAGE, RESPONSE_MESSAGE);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo(
                    "armeria.grpc.testing.UnitTestService/StaticStreamedOutputCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    public void error_noMessage() throws Exception {
        final StatusRuntimeException t = (StatusRuntimeException) catchThrowable(
                () -> blockingClient.errorNoMessage(REQUEST_MESSAGE));
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isNull();

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/ErrorNoMessage");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.ABORTED);
            assertThat(grpcStatus.getDescription()).isNull();
        });
    }

    @Test
    public void error_withMessage() throws Exception {
        final StatusRuntimeException t = (StatusRuntimeException) catchThrowable(
                () -> blockingClient.errorWithMessage(REQUEST_MESSAGE));
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("aborted call");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/ErrorWithMessage");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.ABORTED);
            assertThat(grpcStatus.getDescription()).isEqualTo("aborted call");
        });
    }

    @Test
    public void error_thrown_unary() throws Exception {
        final StatusRuntimeException t = (StatusRuntimeException) catchThrowable(
                () -> blockingClient.unaryThrowsError(REQUEST_MESSAGE));
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("call aborted");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/UnaryThrowsError");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.ABORTED);
            assertThat(grpcStatus.getDescription()).isEqualTo("call aborted");
        });
    }

    @Test
    public void error_thrown_streamMessage() throws Exception {
        final StreamRecorder<SimpleResponse> response = StreamRecorder.create();
        final StreamObserver<SimpleRequest> request = streamingClient.streamThrowsError(response);
        request.onNext(REQUEST_MESSAGE);
        response.awaitCompletion();
        final StatusRuntimeException t = (StatusRuntimeException) response.getError();
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("bad streaming message");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/StreamThrowsError");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.ABORTED);
            assertThat(grpcStatus.getDescription()).isEqualTo("bad streaming message");
        });
    }

    @Test
    public void error_thrown_streamStub() throws Exception {
        final StreamRecorder<SimpleResponse> response = StreamRecorder.create();
        streamingClient.streamThrowsErrorInStub(response);
        response.awaitCompletion();
        final StatusRuntimeException t = (StatusRuntimeException) response.getError();
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("bad streaming stub");

        checkRequestLogStatus(grpcStatus -> {
            assertThat(grpcStatus.getCode()).isEqualTo(Code.ABORTED);
            assertThat(grpcStatus.getDescription()).isEqualTo("bad streaming stub");
        });
    }

    @Test
    public void requestContextSet() throws Exception {
        final StreamRecorder<SimpleResponse> response = StreamRecorder.create();
        final StreamObserver<SimpleRequest> request = streamingClient.checkRequestContext(response);
        request.onNext(REQUEST_MESSAGE);
        request.onNext(REQUEST_MESSAGE);
        request.onNext(REQUEST_MESSAGE);
        request.onCompleted();
        response.awaitCompletion();
        final SimpleResponse expectedResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setBody(ByteString.copyFromUtf8("3")))
                              .build();
        assertThat(response.getValues()).containsExactly(expectedResponse);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/CheckRequestContext");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(expectedResponse);
        });
    }

    @Test
    public void tooLargeRequest_uncompressed() throws Exception {
        final SimpleRequest request = newLargeRequest();
        final StatusRuntimeException t =
                (StatusRuntimeException) catchThrowable(
                        () -> blockingClient.staticUnaryCall(request));

        assertThat(t.getStatus().getCode()).isEqualTo(Code.CANCELLED);

        checkRequestLogStatus(grpcStatus -> {
            assertThat(grpcStatus.getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
        });
    }

    @Test
    public void tooLargeRequest_compressed() throws Exception {
        final SimpleRequest request = newLargeRequest();
        final StatusRuntimeException t =
                (StatusRuntimeException) catchThrowable(
                        () -> blockingClient.withCompression("gzip").staticUnaryCall(request));

        assertThat(t.getStatus().getCode()).isEqualTo(Code.CANCELLED);

        checkRequestLogStatus(grpcStatus -> {
            assertThat(grpcStatus.getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
        });
    }

    private static SimpleRequest newLargeRequest() {
        return SimpleRequest.newBuilder()
                            .setPayload(Payload.newBuilder()
                                               .setBody(ByteString.copyFrom(LARGE_PAYLOAD.toByteArray())))
                            .build();
    }

    @Test
    public void uncompressedClient_compressedEndpoint() throws Exception {
        final ManagedChannel nonDecompressingChannel =
                ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                     .decompressorRegistry(
                                             DecompressorRegistry.emptyInstance()
                                                                 .with(Codec.Identity.NONE, false))
                                     .usePlaintext()
                                     .build();
        final UnitTestServiceBlockingStub client = UnitTestServiceGrpc.newBlockingStub(nonDecompressingChannel);
        assertThat(client.staticUnaryCallSetsMessageCompression(REQUEST_MESSAGE))
                .isEqualTo(RESPONSE_MESSAGE);
        nonDecompressingChannel.shutdownNow();

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo(
                    "armeria.grpc.testing.UnitTestService/StaticUnaryCallSetsMessageCompression");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    public void compressedClient_compressedEndpoint() throws Exception {
        assertThat(blockingClient.staticUnaryCallSetsMessageCompression(REQUEST_MESSAGE))
                .isEqualTo(RESPONSE_MESSAGE);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo(
                    "armeria.grpc.testing.UnitTestService/StaticUnaryCallSetsMessageCompression");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    public void clientSocketClosedBeforeHalfCloseHttp2() throws Exception {
        clientSocketClosedBeforeHalfClose("h2c");
    }

    @Test
    public void clientSocketClosedBeforeHalfCloseHttp1() throws Exception {
        clientSocketClosedBeforeHalfClose("h1c");
    }

    private static void clientSocketClosedBeforeHalfClose(String protocol) throws Exception {
        final ClientFactory factory = new ClientFactoryBuilder().build();
        final UnitTestServiceStub stub =
                new ClientBuilder("gproto+" + protocol + "://127.0.0.1:" + server.httpPort() + '/')
                        .factory(factory)
                        .build(UnitTestServiceStub.class);
        final AtomicReference<SimpleResponse> response = new AtomicReference<>();
        final StreamObserver<SimpleRequest> stream = stub.streamClientCancels(
                new StreamObserver<SimpleResponse>() {
                    @Override
                    public void onNext(SimpleResponse value) {
                        response.set(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        stream.onNext(SimpleRequest.getDefaultInstance());
        await().untilAsserted(() -> assertThat(response).hasValue(SimpleResponse.getDefaultInstance()));
        factory.close();
        await().untilAsserted(() -> assertThat(COMPLETED).hasValue(true));

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/StreamClientCancels");
            assertThat(rpcReq.params()).containsExactly(SimpleRequest.getDefaultInstance());
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(protocol.startsWith("h2") ? Code.CANCELLED
                                                                                 : Code.UNKNOWN);
        });
    }

    @Test
    public void clientSocketClosedAfterHalfCloseBeforeCloseCancelsHttp2() throws Exception {
        clientSocketClosedAfterHalfCloseBeforeCloseCancels(SessionProtocol.H2C);
    }

    @Test
    public void clientSocketClosedAfterHalfCloseBeforeCloseCancelsHttp1() throws Exception {
        clientSocketClosedAfterHalfCloseBeforeCloseCancels(SessionProtocol.H1C);
    }

    private static void clientSocketClosedAfterHalfCloseBeforeCloseCancels(SessionProtocol protocol)
            throws Exception {

        final ClientFactory factory = new ClientFactoryBuilder().build();
        final UnitTestServiceStub stub =
                new ClientBuilder(server.uri(protocol, GrpcSerializationFormats.PROTO, "/"))
                        .factory(factory)
                        .build(UnitTestServiceStub.class);
        final AtomicReference<SimpleResponse> response = new AtomicReference<>();
        stub.streamClientCancelsBeforeResponseClosedCancels(
                SimpleRequest.getDefaultInstance(),
                new StreamObserver<SimpleResponse>() {
                    @Override
                    public void onNext(SimpleResponse value) {
                        response.set(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        await().untilAsserted(() -> assertThat(response).hasValue(SimpleResponse.getDefaultInstance()));
        factory.close();
        CLIENT_CLOSED.set(true);
        await().untilAsserted(() -> assertThat(COMPLETED).hasValue(true));

        final RequestLog log = requestLogQueue.take();
        assertThat(log.availabilities()).contains(RequestLogAvailability.COMPLETE);
        assertThat(log.requestContent()).isNotNull();
        assertThat(log.responseContent()).isNull();
        final RpcRequest rpcReq = (RpcRequest) log.requestContent();
        assertThat(rpcReq.method()).isEqualTo(
                "armeria.grpc.testing.UnitTestService/StreamClientCancelsBeforeResponseClosedCancels");
        assertThat(rpcReq.params()).containsExactly(SimpleRequest.getDefaultInstance());
        assertThat(log.responseCause()).isInstanceOf(AbortedStreamException.class);
    }

    @Test
    public void unframed() throws Exception {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        final SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(RESPONSE_MESSAGE);
        assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo(response.content().length());

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    public void unframed_acceptEncoding() throws Exception {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf")
                           .set(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, "gzip,none"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        final SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(RESPONSE_MESSAGE);
        assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo(response.content().length());

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    public void unframed_streamingApi() throws Exception {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.getStaticStreamedOutputCallMethod().getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                StreamingOutputCallRequest.getDefaultInstance().toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertNoRpcContent();
    }

    @Test
    public void unframed_noContentType() throws Exception {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName()),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertNoRpcContent();
    }

    @Test
    public void unframed_grpcEncoding() throws Exception {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf")
                           .set(GrpcHeaderNames.GRPC_ENCODING, "gzip"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertNoRpcContent();
    }

    @Test
    public void unframed_serviceError() throws Exception {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseStatus(
                                     EchoStatus.newBuilder()
                                               .setCode(Status.DEADLINE_EXCEEDED.getCode().value()))
                             .build();
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                request.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.UNKNOWN);
        });
    }

    @Test
    public void grpcWeb() throws Exception {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/grpc-web"),
                GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())).aggregate().get();
        final byte[] serializedStatusHeader = "grpc-status: 0\r\n".getBytes(StandardCharsets.US_ASCII);
        final byte[] serializedTrailers = Bytes.concat(
                new byte[] { ArmeriaServerCall.TRAILERS_FRAME_HEADER },
                Ints.toByteArray(serializedStatusHeader.length),
                serializedStatusHeader);
        assertThat(response.content().array()).containsExactly(
                Bytes.concat(
                        GrpcTestUtil.uncompressedFrame(
                                GrpcTestUtil.protoByteBuf(RESPONSE_MESSAGE)),
                        serializedTrailers));

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    public void grpcWeb_error() throws Exception {
        final HttpClient client = HttpClient.of(server.httpUri("/"));
        final AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.getErrorWithMessageMethod().getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/grpc-web"),
                GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())).aggregate().get();
        assertThat(response.headers()).contains(entry(GrpcHeaderNames.GRPC_STATUS, "10"),
                                                entry(GrpcHeaderNames.GRPC_MESSAGE, "aborted call"));
    }

    @Test
    public void json() throws Exception {
        final AtomicReference<HttpHeaders> requestHeaders = new AtomicReference<>();
        final UnitTestServiceBlockingStub jsonStub =
                new ClientBuilder(server.httpUri(GrpcSerializationFormats.JSON, "/"))
                        .decorator(client -> new SimpleDecoratingClient<HttpRequest, HttpResponse>(client) {
                            @Override
                            public HttpResponse execute(ClientRequestContext ctx, HttpRequest req)
                                    throws Exception {
                                requestHeaders.set(req.headers());
                                return delegate().execute(ctx, req);
                            }
                        })
                        .build(UnitTestServiceBlockingStub.class);
        final SimpleResponse response = jsonStub.staticUnaryCall(REQUEST_MESSAGE);
        assertThat(response).isEqualTo(RESPONSE_MESSAGE);
        assertThat(requestHeaders.get().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("application/grpc+json");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.method()).isEqualTo("armeria.grpc.testing.UnitTestService/StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    public void noMaxMessageSize() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1",
                                                                  serverWithNoMaxMessageSize.httpPort())
                                                      .usePlaintext()
                                                      .build();

        try {
            UnitTestServiceBlockingStub stub = UnitTestServiceGrpc.newBlockingStub(channel);
            assertThat(stub.staticUnaryCall(REQUEST_MESSAGE)).isEqualTo(RESPONSE_MESSAGE);
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    public void longMaxRequestLimit() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1",
                                                                  serverWithLongMaxRequestLimit.httpPort())
                                                      .usePlaintext()
                                                      .build();
        try {
            UnitTestServiceBlockingStub stub = UnitTestServiceGrpc.newBlockingStub(channel);
            assertThat(stub.staticUnaryCall(REQUEST_MESSAGE)).isEqualTo(RESPONSE_MESSAGE);
        } finally {
            channel.shutdownNow();
        }
    }

    private static void checkRequestLog(RequestLogChecker checker) throws Exception {
        final RequestLog log = requestLogQueue.take();
        assertThat(log.availabilities()).contains(RequestLogAvailability.COMPLETE);

        final RpcRequest rpcReq = (RpcRequest) log.requestContent();
        final RpcResponse rpcRes = (RpcResponse) log.responseContent();
        assertThat(rpcReq).isNotNull();
        assertThat((Object) rpcRes).isNotNull();
        assertThat(rpcReq.serviceType()).isEqualTo(GrpcLogUtil.class);

        final Status grpcStatus;
        if (rpcRes.cause() != null) {
            grpcStatus = ((StatusException) rpcRes.cause()).getStatus();
        } else {
            grpcStatus = null;
        }

        checker.check(rpcReq, rpcRes, grpcStatus);
    }

    private static void checkRequestLogStatus(RequestLogStatusChecker checker) throws Exception {
        final RequestLog log = requestLogQueue.take();
        assertThat(log.availabilities()).contains(RequestLogAvailability.COMPLETE);

        final RpcRequest rpcReq = (RpcRequest) log.requestContent();
        final RpcResponse rpcRes = (RpcResponse) log.responseContent();
        assertThat(rpcReq).isNull();
        assertThat((Object) rpcRes).isNotNull();

        assertThat(rpcRes.cause()).isNotNull();
        checker.check(((StatusException) rpcRes.cause()).getStatus());
    }

    private static void assertNoRpcContent() throws InterruptedException {
        final RequestLog log = requestLogQueue.take();
        assertThat(log.availabilities()).contains(RequestLogAvailability.COMPLETE);
        assertThat(log.requestContent()).isNull();
        assertThat(log.responseContent()).isNull();
    }

    @FunctionalInterface
    private interface RequestLogChecker {
        void check(RpcRequest rpcReq, RpcResponse rpcRes, @Nullable Status grpcStatus) throws Exception;
    }

    @FunctionalInterface
    private interface RequestLogStatusChecker {
        void check(Status grpcStatus) throws Exception;
    }
}
