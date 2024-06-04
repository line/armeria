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

import static com.linecorp.armeria.internal.common.grpc.GrpcTestUtil.REQUEST_MESSAGE;
import static com.linecorp.armeria.internal.common.grpc.GrpcTestUtil.RESPONSE_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.base.Strings;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.RequestTargetCache;
import com.linecorp.armeria.internal.common.grpc.GrpcLogUtil;
import com.linecorp.armeria.internal.common.grpc.GrpcTestUtil;
import com.linecorp.armeria.internal.common.grpc.StreamRecorder;
import com.linecorp.armeria.internal.common.grpc.protocol.Base64Decoder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Codec;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.DecompressorRegistry;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc.ServerReflectionStub;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import testing.grpc.Messages.EchoStatus;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.UnitTestServiceGrpc;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceBlockingStub;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceImplBase;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceStub;

class GrpcServiceServerTest {

    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    private static final byte TRAILERS_FRAME_HEADER = (byte) (1 << 7);

    private static final Key<StringValue> STRING_VALUE_KEY =
            ProtoUtils.keyForProto(StringValue.getDefaultInstance());

    private static final Key<Int32Value> INT_32_VALUE_KEY =
            ProtoUtils.keyForProto(Int32Value.getDefaultInstance());

    private static final Key<String> CUSTOM_VALUE_KEY = Key.of("custom-header",
                                                               Metadata.ASCII_STRING_MARSHALLER);
    private static final Key<StringValue> ERROR_METADATA_KEY =
            ProtoUtils.keyForProto(StringValue.getDefaultInstance());
    private static final AsciiString ERROR_METADATA_HEADER = HttpHeaderNames.of(ERROR_METADATA_KEY.name());

    private static AsciiString LARGE_PAYLOAD;

    @BeforeAll
    static void createLargePayload() {
        LARGE_PAYLOAD = AsciiString.of(Strings.repeat("a", MAX_MESSAGE_SIZE + 1));
    }

    @AfterAll
    static void destroyLargePayload() {
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
            responseObserver.onError(Status.ABORTED.asRuntimeException());
        }

        @Override
        public void errorWithMessage(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final Metadata metadata = new Metadata();
            metadata.put(STRING_VALUE_KEY, StringValue.newBuilder().setValue("custom metadata").build());
            metadata.put(CUSTOM_VALUE_KEY, "custom value");

            final ServiceRequestContext ctx = ServiceRequestContext.current();
            // gRPC wire format allow comma-separated binary headers.
            ctx.mutateAdditionalResponseTrailers(
                    mutator -> mutator.add(
                            INT_32_VALUE_KEY.name(),
                            Base64.getEncoder().encodeToString(
                                    Int32Value.newBuilder().setValue(10).build().toByteArray()) +
                            ',' +
                            Base64.getEncoder().encodeToString(
                                    Int32Value.newBuilder().setValue(20).build().toByteArray())));

            responseObserver.onError(Status.ABORTED.withDescription("aborted call")
                                                   .asRuntimeException(metadata));
        }

        @Override
        public StreamObserver<SimpleRequest> errorFromClient(StreamObserver<SimpleResponse> responseObserver) {
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    // required to ensure connection to client before the client calls onError().
                    responseObserver.onNext(RESPONSE_MESSAGE);
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };
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
            final ServiceRequestContext ctx = ServiceRequestContext.current();
            ctx.setAttr(CHECK_REQUEST_CONTEXT_COUNT, 0);
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    final ServiceRequestContext ctx = ServiceRequestContext.current();
                    ctx.setAttr(CHECK_REQUEST_CONTEXT_COUNT, ctx.attr(CHECK_REQUEST_CONTEXT_COUNT) + 1);
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {
                    final ServiceRequestContext ctx = ServiceRequestContext.current();
                    final int count = ctx.attr(CHECK_REQUEST_CONTEXT_COUNT);
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

        @Override
        public void errorReplaceException(SimpleRequest request,
                                          StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(new IllegalStateException("This error should be replaced"));
        }

        @Override
        public void errorAdditionalMetadata(SimpleRequest request,
                                            StreamObserver<SimpleResponse> responseObserver) {
            ServiceRequestContext.current().mutateAdditionalResponseTrailers(
                    mutator -> mutator.add(
                            ERROR_METADATA_HEADER,
                            Base64.getEncoder().encodeToString(StringValue.newBuilder()
                                                                          .setValue("an error occurred")
                                                                          .build()
                                                                          .toByteArray())));
            responseObserver.onError(new IllegalStateException("This error should have metadata"));
        }

        @Override
        public void grpcContext(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final String contextValue = CONTEXT_KEY.get();
            assertThat(contextValue).isEqualTo("value");
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void timesOut(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            ServiceRequestContext.current().setRequestTimeoutMillis(TimeoutMode.SET_FROM_NOW, 100);
        }
    }

    private static final ServerInterceptor REPLACE_EXCEPTION = new ServerInterceptor() {
        @Override
        public <REQ, RESP> Listener<REQ> interceptCall(ServerCall<REQ, RESP> call, Metadata headers,
                                                       ServerCallHandler<REQ, RESP> next) {
            if (!call.getMethodDescriptor().equals(UnitTestServiceGrpc.getErrorReplaceExceptionMethod())) {
                return next.startCall(call, headers);
            }
            return next.startCall(new SimpleForwardingServerCall<REQ, RESP>(call) {
                @Override
                public void close(Status status, Metadata trailers) {
                    if (status.getCause() instanceof IllegalStateException &&
                        status.getCause().getMessage().equals("This error should be replaced")) {
                        status = status.withDescription("Error was replaced");
                    }
                    delegate().close(status, trailers);
                }
            }, headers);
        }
    };

    private static final Context.Key<String> CONTEXT_KEY = Context.key("CONTEXT_KEY");

    private static final ServerInterceptor ADD_TO_CONTEXT = new ServerInterceptor() {
        @Override
        public <REQ, RESP> Listener<REQ> interceptCall(ServerCall<REQ, RESP> call, Metadata headers,
                                                       ServerCallHandler<REQ, RESP> next) {
            final Context grpcContext = Context.current().withValue(CONTEXT_KEY, "value");
            return Contexts.interceptCall(grpcContext, call, headers, next);
        }
    };

    private static final BlockingQueue<RequestLog> requestLogQueue = new LinkedTransferQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(1);
            sb.maxRequestLength(0);
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);

            sb.decorator(LoggingService.newDecorator());
            sb.service(
                    GrpcService.builder()
                               .maxRequestMessageLength(MAX_MESSAGE_SIZE)
                               .addService(new UnitTestServiceImpl())
                               .intercept(REPLACE_EXCEPTION, ADD_TO_CONTEXT)
                               .enableUnframedRequests(true)
                               .supportedSerializationFormats(GrpcSerializationFormats.values())
                               .build(),
                    service -> service
                            .decorate(LoggingService.newDecorator())
                            .decorate((delegate, ctx, req) -> {
                                ctx.log().whenComplete().thenAccept(requestLogQueue::add);
                                return delegate.serve(ctx, req);
                            }));

            // For simplicity, mount onto subpaths with custom options
            sb.serviceUnder(
                    "/json-preserving/",
                    GrpcService.builder()
                               .addService(new UnitTestServiceImpl())
                               .supportedSerializationFormats(GrpcSerializationFormats.values())
                               .jsonMarshallerFactory(serviceDescriptor -> {
                                   return GrpcJsonMarshaller.builder()
                                                            .jsonMarshallerCustomizer(marshaller -> {
                                                                marshaller.preservingProtoFieldNames(true);
                                                            })
                                                            .build(serviceDescriptor);
                               })
                               .build());
            sb.serviceUnder(
                    "/no-client-timeout/",
                    GrpcService.builder()
                               .addService(new UnitTestServiceImpl())
                               .useClientTimeoutHeader(false)
                               .build());

            sb.service(
                    GrpcService.builder()
                               .addService(ProtoReflectionService.newInstance())
                               .build(),
                    service -> service.decorate(LoggingService.newDecorator()));
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithBlockingExecutor = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(1);
            sb.maxRequestLength(0);

            sb.serviceUnder("/",
                            GrpcService.builder()
                                       .maxRequestMessageLength(MAX_MESSAGE_SIZE)
                                       .addService(new UnitTestServiceImpl())
                                       .intercept(REPLACE_EXCEPTION, ADD_TO_CONTEXT)
                                       .enableUnframedRequests(true)
                                       .supportedSerializationFormats(
                                               GrpcSerializationFormats.values())
                                       .useBlockingTaskExecutor(true)
                                       .build()
                                       .decorate(LoggingService.newDecorator())
                                       .decorate((delegate, ctx, req) -> {
                                           ctx.log().whenComplete().thenAccept(requestLogQueue::add);
                                           return delegate.serve(ctx, req);
                                       }));
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithNoMaxMessageSize = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(1);
            sb.maxRequestLength(0);

            sb.serviceUnder("/",
                            GrpcService.builder()
                                       .addService(new UnitTestServiceImpl())
                                       .build()
                                       .decorate(LoggingService.newDecorator())
                                       .decorate((delegate, ctx, req) -> {
                                           ctx.log().whenComplete().thenAccept(requestLogQueue::add);
                                           return delegate.serve(ctx, req);
                                       }));
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithLongMaxRequestLimit = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(1);
            sb.maxRequestLength(Long.MAX_VALUE);

            sb.serviceUnder("/",
                            GrpcService.builder()
                                       .addService(new UnitTestServiceImpl())
                                       .build()
                                       .decorate(LoggingService.newDecorator())
                                       .decorate((delegate, ctx, req) -> {
                                           ctx.log().whenComplete().thenAccept(requestLogQueue::add);
                                           return delegate.serve(ctx, req);
                                       }));
        }
    };

    private static ManagedChannel channel;
    private static ManagedChannel blockingChannel;

    @BeforeAll
    static void setUpChannel() {
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                       .usePlaintext()
                                       .build();
        blockingChannel = ManagedChannelBuilder.forAddress("127.0.0.1", serverWithBlockingExecutor.httpPort())
                                               .usePlaintext()
                                               .build();
    }

    @AfterAll
    static void tearDownChannel() {
        channel.shutdownNow();
        blockingChannel.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        COMPLETED.set(false);
        CLIENT_CLOSED.set(false);

        RequestTargetCache.clearCachedPaths();
    }

    @AfterEach
    void tearDown() {
        // Make sure all RequestLogs are consumed by the test.
        try {
            assertThat(requestLogQueue).isEmpty();
        } finally {
            requestLogQueue.clear();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void unary_normal(UnitTestServiceBlockingStub blockingClient) throws Exception {
        assertThat(blockingClient.staticUnaryCall(REQUEST_MESSAGE)).isEqualTo(RESPONSE_MESSAGE);

        // Confirm gRPC paths are cached despite using serviceUnder
        await().untilAsserted(() -> assertThat(RequestTargetCache.cachedServerPaths())
                .contains("/armeria.grpc.testing.UnitTestService/StaticUnaryCall"));

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @ParameterizedTest
    @ArgumentsSource(StreamingClientProvider.class)
    void streamedOutput_normal(UnitTestServiceStub streamingClient) throws Exception {
        final StreamRecorder<SimpleResponse> recorder = StreamRecorder.create();
        streamingClient.staticStreamedOutputCall(REQUEST_MESSAGE, recorder);
        recorder.awaitCompletion();
        assertThat(recorder.getValues()).containsExactly(RESPONSE_MESSAGE, RESPONSE_MESSAGE);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticStreamedOutputCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void error_noMessage(UnitTestServiceBlockingStub blockingClient) throws Exception {
        final StatusRuntimeException t = (StatusRuntimeException) catchThrowable(
                () -> blockingClient.errorNoMessage(REQUEST_MESSAGE));
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isNull();

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("ErrorNoMessage");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.ABORTED);
            assertThat(grpcStatus.getDescription()).isNull();
        });
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void error_withMessage(UnitTestServiceBlockingStub blockingClient) throws Exception {
        final StatusRuntimeException t = (StatusRuntimeException) catchThrowable(
                () -> blockingClient.errorWithMessage(REQUEST_MESSAGE));
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("aborted call");
        assertThat(t.getTrailers().getAll(STRING_VALUE_KEY))
                .containsExactly(StringValue.newBuilder().setValue("custom metadata").build());
        assertThat(t.getTrailers().getAll(INT_32_VALUE_KEY))
                .containsExactly(Int32Value.newBuilder().setValue(10).build(),
                                 Int32Value.newBuilder().setValue(20).build());
        assertThat(t.getTrailers().get(CUSTOM_VALUE_KEY)).isEqualTo("custom value");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("ErrorWithMessage");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.ABORTED);
            assertThat(grpcStatus.getDescription()).isEqualTo("aborted call");
            assertThat(rpcRes.cause()).isInstanceOfSatisfying(StatusRuntimeException.class, ex -> {
                assertThat(ex.getStatus().getCode()).isEqualTo(Code.ABORTED);
                assertThat(ex.getStatus().getDescription()).isEqualTo("aborted call");
                assertThat(ex.getTrailers().getAll(STRING_VALUE_KEY))
                        .containsExactly(StringValue.newBuilder().setValue("custom metadata").build());
                // INT_32_VALUE_KEY is not included here, since the key is directly injected to response header.
                assertThat(ex.getTrailers().get(CUSTOM_VALUE_KEY)).isEqualTo("custom value");
            });
        });
    }

    @ParameterizedTest
    @ArgumentsSource(StreamingClientProvider.class)
    void error_fromClient(UnitTestServiceStub streamingClient) throws Exception {
        final StreamRecorder<SimpleResponse> response = StreamRecorder.create();
        final StreamObserver<SimpleRequest> request = streamingClient.errorFromClient(response);
        // Sending request and receiving response is required to ensure connection before calling onError.
        request.onNext(REQUEST_MESSAGE);
        response.firstValue().get();
        final Metadata meta = new Metadata();
        meta.put(STRING_VALUE_KEY, StringValue.newBuilder().setValue("client").build());
        request.onError(Status.INVALID_ARGUMENT.withDescription("abort from client")
                                               .asRuntimeException(meta));
        response.awaitCompletion();

        assertThat(response.getError()).isInstanceOfSatisfying(StatusRuntimeException.class, ex -> {
            assertThat(ex.getStatus()).isNotNull();
            assertThat(ex.getStatus().getCode()).isEqualTo(Code.CANCELLED);
        });

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("ErrorFromClient");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);

            assertThat(rpcRes.cause()).isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                assertThat(cause.getStatus()).isNotNull();
                assertThat(cause.getStatus().getCode()).isEqualTo(Code.CANCELLED);
                assertThat(cause.getTrailers()).isNotNull();
                assertThat(cause.getTrailers().keys()).isEmpty();
                // Different from GrpcClientTest::errorFromClient server doesn't know the cause in detail,
                // since ArmeriaClientCall doesn't send cause on error
                assertThat(cause.getCause()).isInstanceOf(ClosedStreamException.class);
            });
        });
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void error_thrown_unary(UnitTestServiceBlockingStub blockingClient) throws Exception {
        final StatusRuntimeException t = (StatusRuntimeException) catchThrowable(
                () -> blockingClient.unaryThrowsError(REQUEST_MESSAGE));
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("call aborted");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("UnaryThrowsError");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.ABORTED);
            assertThat(grpcStatus.getDescription()).isEqualTo("call aborted");
        });
    }

    @ParameterizedTest
    @ArgumentsSource(StreamingClientProvider.class)
    void error_thrown_streamMessage(UnitTestServiceStub streamingClient) throws Exception {
        final StreamRecorder<SimpleResponse> response = StreamRecorder.create();
        final StreamObserver<SimpleRequest> request = streamingClient.streamThrowsError(response);
        request.onNext(REQUEST_MESSAGE);
        response.awaitCompletion();
        final StatusRuntimeException t = (StatusRuntimeException) response.getError();
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("bad streaming message");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StreamThrowsError");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.ABORTED);
            assertThat(grpcStatus.getDescription()).isEqualTo("bad streaming message");
        });
    }

    @ParameterizedTest
    @ArgumentsSource(StreamingClientProvider.class)
    void error_thrown_streamStub(UnitTestServiceStub streamingClient) throws Exception {
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

    @ParameterizedTest
    @ArgumentsSource(StreamingClientProvider.class)
    void requestContextSet(UnitTestServiceStub streamingClient) throws Exception {
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
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("CheckRequestContext");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(expectedResponse);
        });
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void tooLargeRequest_uncompressed(UnitTestServiceBlockingStub blockingClient) throws Exception {
        final SimpleRequest request = newLargeRequest();
        final StatusRuntimeException t =
                (StatusRuntimeException) catchThrowable(
                        () -> blockingClient.staticUnaryCall(request));

        assertThat(t.getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);

        checkRequestLogStatus(grpcStatus -> {
            assertThat(grpcStatus.getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
        });
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void tooLargeRequest_compressed(UnitTestServiceBlockingStub blockingClient) throws Exception {
        final SimpleRequest request = newLargeRequest();
        final StatusRuntimeException t =
                (StatusRuntimeException) catchThrowable(
                        () -> blockingClient.withCompression("gzip").staticUnaryCall(request));

        assertThat(t.getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);

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
    void ignoreClientTimeout() {
        final UnitTestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri() + "/no-client-timeout/")
                           .build(UnitTestServiceBlockingStub.class)
                           .withDeadlineAfter(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> client.timesOut(
                SimpleRequest.getDefaultInstance())).isInstanceOfSatisfying(
                StatusRuntimeException.class, t ->
                        assertThat(t.getStatus().getCode()).isEqualTo(Code.CANCELLED));
    }

    @Test
    void uncompressedClient_compressedEndpoint() throws Exception {
        final ManagedChannel nonDecompressingChannel =
                ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                     .decompressorRegistry(
                                             DecompressorRegistry.emptyInstance()
                                                                 .with(Codec.Identity.NONE, false))
                                     .usePlaintext()
                                     .build();
        final UnitTestServiceBlockingStub client = UnitTestServiceGrpc.newBlockingStub(
                nonDecompressingChannel);
        assertThat(client.staticUnaryCallSetsMessageCompression(REQUEST_MESSAGE))
                .isEqualTo(RESPONSE_MESSAGE);
        nonDecompressingChannel.shutdownNow();

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticUnaryCallSetsMessageCompression");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void compressedClient_compressedEndpoint(UnitTestServiceBlockingStub blockingClient) throws Exception {
        assertThat(blockingClient.staticUnaryCallSetsMessageCompression(REQUEST_MESSAGE))
                .isEqualTo(RESPONSE_MESSAGE);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticUnaryCallSetsMessageCompression");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    void clientSocketClosedBeforeHalfCloseHttp2() throws Exception {
        clientSocketClosedBeforeHalfClose("h2c");
    }

    @Test
    void clientSocketClosedBeforeHalfCloseHttp1() throws Exception {
        clientSocketClosedBeforeHalfClose("h1c");
    }

    private static void clientSocketClosedBeforeHalfClose(String protocol) throws Exception {
        final UnitTestServiceStub stub =
                GrpcClients.builder(protocol + "://127.0.0.1:" + server.httpPort() + '/')
                           .decorator(LoggingClient.newDecorator())
                           .build(UnitTestServiceStub.class);
        final AtomicReference<SimpleResponse> response = new AtomicReference<>();
        final StreamObserver<SimpleRequest> stream;
        final ClientRequestContext clientRequestContext;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            stream = stub.streamClientCancels(
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
            clientRequestContext = captor.get();
        }
        stream.onNext(SimpleRequest.getDefaultInstance());
        await().untilAsserted(() -> assertThat(response).hasValue(SimpleResponse.getDefaultInstance()));
        clientRequestContext.cancel();
        await().untilAsserted(() -> assertThat(COMPLETED).hasValue(true));

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StreamClientCancels");
            assertThat(rpcReq.params()).containsExactly(SimpleRequest.getDefaultInstance());
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.CANCELLED);
        });
    }

    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    @ParameterizedTest
    void clientSocketClosedAfterHalfCloseBeforeCloseCancels(SessionProtocol protocol)
            throws Exception {

        final UnitTestServiceStub stub =
                GrpcClients.builder(server.uri(protocol))
                           .build(UnitTestServiceStub.class);
        final AtomicReference<SimpleResponse> response = new AtomicReference<>();
        final ClientRequestContext clientRequestContext;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
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
            clientRequestContext = captor.get();
        }
        await().untilAsserted(() -> assertThat(response).hasValue(SimpleResponse.getDefaultInstance()));

        clientRequestContext.cancel();
        CLIENT_CLOSED.set(true);
        await().untilAsserted(() -> assertThat(COMPLETED).hasValue(true));

        final RequestLog log = requestLogQueue.take();
        assertThat(log.isComplete()).isTrue();
        assertThat(log.requestContent()).isNotNull();

        final RpcResponse rpcResponse = (RpcResponse) log.responseContent();
        final StatusRuntimeException cause = (StatusRuntimeException) rpcResponse.cause();
        assertThat(cause.getStatus().getCode()).isEqualTo(Code.CANCELLED);
        if (protocol.isMultiplex()) {
            assertThat(cause.getStatus().getCause()).isInstanceOf(ClosedStreamException.class);
        } else {
            assertThat(cause.getStatus().getCause()).isInstanceOf(ClosedSessionException.class);
        }

        final RpcRequest rpcReq = (RpcRequest) log.requestContent();
        assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
        assertThat(rpcReq.method()).isEqualTo("StreamClientCancelsBeforeResponseClosedCancels");
        assertThat(rpcReq.params()).containsExactly(SimpleRequest.getDefaultInstance());
    }

    @Test
    void unframed() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST,
                                  UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName(),
                                  HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        final SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(RESPONSE_MESSAGE);
        assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo(response.content().length());

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    void unframed_acceptEncoding() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST,
                                  UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName(),
                                  HttpHeaderNames.CONTENT_TYPE, "application/protobuf",
                                  GrpcHeaderNames.GRPC_ACCEPT_ENCODING, "gzip,none"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        final SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(RESPONSE_MESSAGE);
        assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo(response.content().length());

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    void unframed_streamingApi() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST,
                                  UnitTestServiceGrpc.getStaticStreamedOutputCallMethod()
                                                     .getFullMethodName(),
                                  HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                StreamingOutputCallRequest.getDefaultInstance().toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertNoRpcContent();
    }

    @Test
    void unframed_noContentType() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST,
                                  UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName()),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertNoRpcContent();
    }

    @Test
    void unframed_grpcEncoding() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST,
                                  UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName(),
                                  HttpHeaderNames.CONTENT_TYPE, "application/protobuf",
                                  GrpcHeaderNames.GRPC_ENCODING, "gzip"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertNoRpcContent();
    }

    @Test
    void unframed_serviceError() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseStatus(
                                     EchoStatus.newBuilder()
                                               .setCode(Status.DEADLINE_EXCEEDED.getCode().value()))
                             .build();
        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST,
                                  UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName(),
                                  HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                request.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(request);
            assertThat(grpcStatus).isNotNull();
            assertThat(grpcStatus.getCode()).isEqualTo(Code.UNKNOWN);
        });
    }

    @Test
    void grpcWeb() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST,
                                  UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName(),
                                  HttpHeaderNames.CONTENT_TYPE, "application/grpc-web"),
                GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())).aggregate().get();
        final byte[] serializedStatusHeader = "grpc-status: 0\r\n".getBytes(StandardCharsets.US_ASCII);
        final byte[] serializedTrailers = Bytes.concat(
                new byte[] { TRAILERS_FRAME_HEADER },
                Ints.toByteArray(serializedStatusHeader.length),
                serializedStatusHeader);
        assertThat(response.content().array()).containsExactly(
                Bytes.concat(
                        GrpcTestUtil.uncompressedFrame(
                                GrpcTestUtil.protoByteBuf(RESPONSE_MESSAGE)),
                        serializedTrailers));

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    void grpcWebText() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final byte[] body = GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf());
        final HttpResponse httpResponse = client.execute(
                RequestHeaders.of(HttpMethod.POST,
                                  UnitTestServiceGrpc.getStaticUnaryCallMethod().getFullMethodName(),
                                  HttpHeaderNames.CONTENT_TYPE, "application/grpc-web-text"),
                Base64.getEncoder().encode(body));
        final AggregatedHttpResponse response = httpResponse.mapData(data -> {
            final ByteBuf buf = data.byteBuf();
            final Base64Decoder decoder = new Base64Decoder(UnpooledByteBufAllocator.DEFAULT);
            return HttpData.wrap(decoder.decode(buf));
        }).aggregate().join();
        // Make sure that a pooled HttpData was created while mapping is released.
        assertThat(response.content().isPooled()).isFalse();

        final byte[] serializedStatusHeader = "grpc-status: 0\r\n".getBytes(StandardCharsets.US_ASCII);
        final byte[] serializedTrailers = Bytes.concat(
                new byte[] { TRAILERS_FRAME_HEADER },
                Ints.toByteArray(serializedStatusHeader.length),
                serializedStatusHeader);
        assertThat(response.content().array()).containsExactly(
                Bytes.concat(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.protoByteBuf(RESPONSE_MESSAGE)),
                             serializedTrailers));

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });
    }

    @Test
    void grpcWeb_error() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.execute(
                RequestHeaders.of(HttpMethod.POST,
                                  UnitTestServiceGrpc.getErrorWithMessageMethod().getFullMethodName(),
                                  HttpHeaderNames.CONTENT_TYPE, "application/grpc-web"),
                GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())).aggregate().get();
        assertThat(response.headers()).contains(entry(GrpcHeaderNames.GRPC_STATUS, "10"),
                                                entry(GrpcHeaderNames.GRPC_MESSAGE, "aborted call"));
        requestLogQueue.take();
    }

    @Test
    void json() throws Exception {
        final AtomicReference<HttpHeaders> requestHeaders = new AtomicReference<>();
        final AtomicReference<byte[]> payload = new AtomicReference<>();
        final UnitTestServiceBlockingStub jsonStub =
                GrpcClients.builder(server.httpUri())
                           .serializationFormat(GrpcSerializationFormats.JSON)
                           .decorator(client -> new SimpleDecoratingHttpClient(client) {
                               @Override
                               public HttpResponse execute(ClientRequestContext ctx, HttpRequest req)
                                       throws Exception {
                                   requestHeaders.set(req.headers());
                                   return unwrap().execute(ctx, req)
                                                  .peekData(data -> payload.set(data.array()));
                               }
                           })
                           .build(UnitTestServiceBlockingStub.class);
        final SimpleResponse response = jsonStub.staticUnaryCall(REQUEST_MESSAGE);
        assertThat(response).isEqualTo(RESPONSE_MESSAGE);
        assertThat(requestHeaders.get().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                "application/grpc+json");

        checkRequestLog((rpcReq, rpcRes, grpcStatus) -> {
            assertThat(rpcReq.serviceName()).isEqualTo("armeria.grpc.testing.UnitTestService");
            assertThat(rpcReq.method()).isEqualTo("StaticUnaryCall");
            assertThat(rpcReq.params()).containsExactly(REQUEST_MESSAGE);
            assertThat(rpcRes.get()).isEqualTo(RESPONSE_MESSAGE);
        });

        final byte[] deframed = Arrays.copyOfRange(payload.get(), 5, payload.get().length);
        assertThat(new String(deframed, StandardCharsets.UTF_8)).contains("oauthScope");
    }

    @Test
    void json_preservingFieldNames() throws Exception {
        final AtomicReference<HttpHeaders> requestHeaders = new AtomicReference<>();
        final AtomicReference<byte[]> payload = new AtomicReference<>();
        final Function<ServiceDescriptor, GrpcJsonMarshaller> marshallerFactory = serviceDescriptor -> {
            return GrpcJsonMarshaller.builder()
                                     .jsonMarshallerCustomizer(marshaller -> {
                                         marshaller.preservingProtoFieldNames(true);
                                     })
                                     .build(serviceDescriptor);
        };
        final UnitTestServiceBlockingStub jsonStub =
                GrpcClients.builder(server.httpUri() + "/json-preserving/")
                           .serializationFormat(GrpcSerializationFormats.JSON)
                           .jsonMarshallerFactory(marshallerFactory)
                           .decorator(client -> new SimpleDecoratingHttpClient(client) {
                               @Override
                               public HttpResponse execute(ClientRequestContext ctx, HttpRequest req)
                                       throws Exception {
                                   requestHeaders.set(req.headers());
                                   return unwrap().execute(ctx, req)
                                                  .peekData(data -> payload.set(data.array()));
                               }
                           })
                           .build(UnitTestServiceBlockingStub.class);
        final SimpleResponse response = jsonStub.staticUnaryCall(REQUEST_MESSAGE);
        assertThat(response).isEqualTo(RESPONSE_MESSAGE);
        assertThat(requestHeaders.get().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo(
                "application/grpc+json");

        final byte[] deframed = Arrays.copyOfRange(payload.get(), 5, payload.get().length);
        assertThat(new String(deframed, StandardCharsets.UTF_8)).contains("oauth_scope");
    }

    @Test
    void noMaxMessageSize() throws Exception {
        final ManagedChannel channel =
                ManagedChannelBuilder.forAddress("127.0.0.1", serverWithNoMaxMessageSize.httpPort())
                                     .usePlaintext()
                                     .build();

        try {
            final UnitTestServiceBlockingStub stub = UnitTestServiceGrpc.newBlockingStub(channel);
            assertThat(stub.staticUnaryCall(REQUEST_MESSAGE)).isEqualTo(RESPONSE_MESSAGE);
        } finally {
            channel.shutdownNow();
            requestLogQueue.take();
        }
    }

    @Test
    void longMaxRequestLimit() throws Exception {
        final ManagedChannel channel =
                ManagedChannelBuilder.forAddress("127.0.0.1", serverWithLongMaxRequestLimit.httpPort())
                                     .usePlaintext()
                                     .build();
        try {
            final UnitTestServiceBlockingStub stub = UnitTestServiceGrpc.newBlockingStub(channel);
            assertThat(stub.staticUnaryCall(REQUEST_MESSAGE)).isEqualTo(RESPONSE_MESSAGE);
        } finally {
            channel.shutdownNow();
            requestLogQueue.take();
        }
    }

    @Test
    void reflectionService() throws Exception {
        final ServerReflectionStub stub = ServerReflectionGrpc.newStub(channel);

        final AtomicReference<ServerReflectionResponse> response = new AtomicReference<>();

        final StreamObserver<ServerReflectionRequest> request = stub.serverReflectionInfo(
                new StreamObserver<ServerReflectionResponse>() {
                    @Override
                    public void onNext(ServerReflectionResponse value) {
                        response.set(value);
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {}
                });
        request.onNext(ServerReflectionRequest.newBuilder()
                                              .setListServices("")
                                              .build());
        request.onCompleted();

        await().untilAsserted(
                () -> {
                    assertThat(response).doesNotHaveValue(null);
                    // Instead of making this test depend on every other one, just check that there is at
                    // least two services returned corresponding to UnitTestService and
                    // ProtoReflectionService.
                    assertThat(response.get().getListServicesResponse().getServiceList())
                            .hasSizeGreaterThanOrEqualTo(2);
                });
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void replaceException(UnitTestServiceBlockingStub blockingClient) throws Exception {
        assertThatThrownBy(() -> blockingClient.errorReplaceException(SimpleRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessage("UNKNOWN: Error was replaced");

        requestLogQueue.take();
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void errorAdditionalMetadata(UnitTestServiceBlockingStub blockingClient) throws Exception {
        final Throwable t = catchThrowable(
                () -> blockingClient.errorAdditionalMetadata(SimpleRequest.getDefaultInstance()));
        assertThat(t).isInstanceOfSatisfying(StatusRuntimeException.class, error -> {
            assertThat(error).hasMessage("UNKNOWN");
            assertThat(error.getTrailers().keys()).contains(ERROR_METADATA_HEADER.toString());
            assertThat(error.getTrailers().get(ERROR_METADATA_KEY).getValue()).isEqualTo(
                    "an error occurred");
        });

        requestLogQueue.take();
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void grpcContext(UnitTestServiceBlockingStub blockingClient) throws Exception {
        assertThat(blockingClient.grpcContext(SimpleRequest.getDefaultInstance()))
                .isEqualTo(SimpleResponse.getDefaultInstance());

        requestLogQueue.take();
    }

    private static class BlockingClientProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(UnitTestServiceGrpc.newBlockingStub(channel),
                             UnitTestServiceGrpc.newBlockingStub(blockingChannel))
                         .map(Arguments::of);
        }
    }

    private static class StreamingClientProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(UnitTestServiceGrpc.newStub(channel),
                             UnitTestServiceGrpc.newStub(blockingChannel))
                         .map(Arguments::of);
        }
    }

    private static void checkRequestLog(RequestLogChecker checker) throws Exception {
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

    private static void checkRequestLogStatus(RequestLogStatusChecker checker) throws Exception {
        final RequestLog log = requestLogQueue.take();
        assertThat(log.isComplete()).isTrue();

        final RpcRequest rpcReq = (RpcRequest) log.requestContent();
        final RpcResponse rpcRes = (RpcResponse) log.responseContent();
        assertThat(rpcReq).isNotNull();
        assertThat((Object) rpcRes).isNotNull();

        assertThat(rpcRes.cause()).isNotNull();
        checker.check(((StatusRuntimeException) rpcRes.cause()).getStatus());
    }

    private static void assertNoRpcContent() throws InterruptedException {
        final RequestLog log = requestLogQueue.take();
        assertThat(log.isComplete()).isTrue();
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
