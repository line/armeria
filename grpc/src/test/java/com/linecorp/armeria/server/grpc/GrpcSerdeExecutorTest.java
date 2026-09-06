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
import static org.awaitility.Awaitility.await;

import java.io.InputStream;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.FilteredHttpRequest;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.grpc.AbstractServerCall;
import com.linecorp.armeria.internal.testing.BlockingUtils;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Codec;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.PrototypeMarshaller;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.ResponseParameters;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceStub;

/**
 * Verifies which thread performs request deserialization and response serialization.
 */
class GrpcSerdeExecutorTest {

    private static final String CALLER_THREAD_NAME = "serde-caller";

    /**
     * The thread from which the services below send their responses, so that a test can tell whether
     * a response was serialized on the thread that called {@code sendMessage()}.
     */
    private static final Executor caller =
            Executors.newSingleThreadExecutor(r -> new Thread(r, CALLER_THREAD_NAME));

    private static final AtomicReference<Thread> requestParseThread = new AtomicReference<>();
    private static final AtomicInteger requestParseCount = new AtomicInteger();
    private static final AtomicReference<Thread> responseStreamThread = new AtomicReference<>();
    private static final AtomicReference<Thread> handlerThread = new AtomicReference<>();
    private static final AtomicReference<CompletableFuture<Void>> requestReceived = new AtomicReference<>();
    private static final AtomicReference<CompletableFuture<Void>> requestHalfClosed = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> requestParseStarted = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> requestParseRelease = new AtomicReference<>();
    private static final AtomicReference<RuntimeException> requestParseFailure = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> requestCompleteStarted = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> requestCompleteRelease = new AtomicReference<>();
    private static final AtomicReference<ServerCall<?, ?>> blockingServerCall = new AtomicReference<>();

    private static final TrackingAllocator cancellingServerAllocator = new TrackingAllocator();
    private static final TrackingAllocator leakServerAllocator = new TrackingAllocator();

    // Non-empty messages, because an empty message is not (de)serialized through the marshaller.
    private static final SimpleRequest UNARY_REQUEST =
            SimpleRequest.newBuilder().setResponseSize(1).build();
    private static final SimpleResponse UNARY_RESPONSE =
            SimpleResponse.newBuilder()
                          .setPayload(Payload.newBuilder().setBody(ByteString.copyFromUtf8("response")))
                          .build();
    private static final StreamingOutputCallRequest BIDI_REQUEST =
            StreamingOutputCallRequest.newBuilder()
                                      .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                      .build();
    private static final StreamingOutputCallResponse BIDI_RESPONSE =
            StreamingOutputCallResponse.newBuilder()
                                       .setPayload(Payload.newBuilder()
                                                          .setBody(ByteString.copyFromUtf8("response")))
                                       .build();

    @RegisterExtension
    static final ServerExtension blockingServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(ServerInterceptors.intercept(
                                          recordingService(), new ServerInterceptor() {
                                              @Override
                                              public <I, O> Listener<I> interceptCall(
                                                      ServerCall<I, O> call, Metadata headers,
                                                      ServerCallHandler<I, O> next) {
                                                  blockingServerCall.set(call);
                                                  return next.startCall(call, headers);
                                              }
                                          }))
                                  .useMethodMarshaller(true)
                                  .useBlockingTaskExecutor(true)
                                  .build());
            sb.decorator((delegate, ctx, req) -> {
                final HttpRequest observed = new FilteredHttpRequest(req) {
                    @Override
                    protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
                        final CountDownLatch started = requestCompleteStarted.get();
                        if (started != null) {
                            ctx.eventLoop().execute(() -> {
                                started.countDown();
                                final CountDownLatch release = requestCompleteRelease.get();
                                assert release != null;
                                BlockingUtils.blockingRun(() -> release.await(10, TimeUnit.SECONDS));
                            });
                        }
                    }

                    @Override
                    protected HttpObject filter(HttpObject obj) {
                        return obj;
                    }
                };
                ctx.updateRequest(observed);
                return delegate.serve(ctx, observed);
            });
        }
    };

    @RegisterExtension
    static final ServerExtension eventLoopServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(recordingService())
                                  .useMethodMarshaller(true)
                                  .build());
        }
    };

    /**
     * A blocking server whose per-call blocking executor is stalled until the request is cancelled by
     * the request timeout, so that the deserialization task runs only after the cancellation.
     */
    @RegisterExtension
    static final ServerExtension cancellingServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final AtomicReference<ServerCall<?, ?>> serverCallCaptor = new AtomicReference<>();
            sb.service(GrpcService.builder()
                                  .addService(ServerInterceptors.intercept(
                                          recordingService(), new ServerInterceptor() {
                                              @Override
                                              public <I, O> Listener<I> interceptCall(
                                                      ServerCall<I, O> call, Metadata headers,
                                                      ServerCallHandler<I, O> next) {
                                                  serverCallCaptor.set(call);
                                                  return next.startCall(call, headers);
                                              }
                                          }))
                                  .useMethodMarshaller(true)
                                  .useBlockingTaskExecutor(true)
                                  // Otherwise, the client's `grpc-timeout` overrides the request timeout.
                                  .useClientTimeoutHeader(false)
                                  .build());
            sb.decorator((delegate, ctx, req) -> {
                final HttpRequest stalled = new FilteredHttpRequest(req) {
                    @Override
                    protected void beforeSubscribe(Subscriber<? super HttpObject> subscriber,
                                                   Subscription subscription) {
                        // Called right before the request body is subscribed, i.e. after `startCall()` and
                        // before the first message is deframed. Stall the sequential blocking executor so
                        // that the deserialization task is queued behind and runs after the cancellation.
                        final ServerCall<?, ?> serverCall = serverCallCaptor.get();
                        assertThat(serverCall).isInstanceOf(AbstractServerCall.class);
                        ((AbstractServerCall<?, ?>) serverCall).blockingExecutor().execute(() -> {
                            await().until(serverCall::isCancelled);
                        });
                    }

                    @Override
                    protected HttpObject filter(HttpObject obj) {
                        return obj;
                    }
                };
                ctx.updateRequest(stalled);
                return delegate.serve(ctx, stalled);
            });
            sb.childChannelOption(ChannelOption.ALLOCATOR, cancellingServerAllocator);
            sb.requestTimeoutMillis(200);
        }
    };

    /**
     * A blocking server which enables gzip compression and then blocks the event loop until the first
     * response has been sent, so that the response is serialized before the event loop processes
     * {@code sendHeaders()}.
     */
    @RegisterExtension
    static final ServerExtension compressingServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final MethodDescriptor<SimpleRequest, SimpleResponse> unaryMethod =
                    recording(TestServiceGrpc.getUnaryCallMethod());
            final ServerServiceDefinition service =
                    ServerServiceDefinition
                            .builder(TestServiceGrpc.SERVICE_NAME)
                            .addMethod(unaryMethod, ServerCalls.asyncUnaryCall((request, rawObserver) -> {
                                final ServerCallStreamObserver<SimpleResponse> observer =
                                        (ServerCallStreamObserver<SimpleResponse>) rawObserver;
                                final ServiceRequestContext ctx = ServiceRequestContext.current();
                                observer.setCompression("gzip");

                                final CountDownLatch latch = new CountDownLatch(1);
                                ctx.eventLoop().execute(() -> BlockingUtils.blockingRun(
                                        () -> latch.await(10, TimeUnit.SECONDS)));
                                try {
                                    // Sends the headers and the message back to back.
                                    observer.onNext(UNARY_RESPONSE);
                                } finally {
                                    latch.countDown();
                                }
                                observer.onCompleted();
                            }))
                            .build();
            sb.service(GrpcService.builder()
                                  .addService(service)
                                  .useMethodMarshaller(true)
                                  .useBlockingTaskExecutor(true)
                                  .build());
        }
    };

    /**
     * A blocking server whose services discard a response after {@code sendMessage()}: the unary service
     * fails the call after sending a message, and the bidi service sends a message after the client
     * cancelled the call.
     */
    @RegisterExtension
    static final ServerExtension leakServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final MethodDescriptor<SimpleRequest, SimpleResponse> unaryMethod =
                    recording(TestServiceGrpc.getUnaryCallMethod());
            final MethodDescriptor<StreamingOutputCallRequest, StreamingOutputCallResponse> bidiMethod =
                    recording(TestServiceGrpc.getFullDuplexCallMethod());
            final ServerServiceDefinition service =
                    ServerServiceDefinition
                            .builder(TestServiceGrpc.SERVICE_NAME)
                            .addMethod(unaryMethod, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                                responseObserver.onNext(UNARY_RESPONSE);
                                responseObserver.onError(Status.INTERNAL.asRuntimeException());
                            }))
                            .addMethod(bidiMethod, ServerCalls.asyncBidiStreamingCall(rawObserver -> {
                                final ServerCallStreamObserver<StreamingOutputCallResponse> observer =
                                        (ServerCallStreamObserver<StreamingOutputCallResponse>) rawObserver;
                                // Without an `onCancelHandler`, the stub marks the observer as cancelled when
                                // `onCancel()` is delivered and `onNext()` throws instead of sending.
                                observer.setOnCancelHandler(() -> {});
                                return new StreamObserver<StreamingOutputCallRequest>() {
                                    @Override
                                    public void onNext(StreamingOutputCallRequest value) {
                                        requestReceived.get().complete(null);
                                        await().until(observer::isCancelled);
                                        observer.onNext(BIDI_RESPONSE);
                                    }

                                    @Override
                                    public void onError(Throwable t) {}

                                    @Override
                                    public void onCompleted() {}
                                };
                            }))
                            .build();
            sb.service(GrpcService.builder()
                                  .addService(service)
                                  .useMethodMarshaller(true)
                                  .useBlockingTaskExecutor(true)
                                  .build());
            sb.childChannelOption(ChannelOption.ALLOCATOR, leakServerAllocator);
        }
    };

    @BeforeEach
    void reset() {
        requestParseThread.set(null);
        requestParseCount.set(0);
        responseStreamThread.set(null);
        handlerThread.set(null);
        requestReceived.set(new CompletableFuture<>());
        requestHalfClosed.set(new CompletableFuture<>());
        requestParseStarted.set(null);
        requestParseRelease.set(null);
        requestParseFailure.set(null);
        requestCompleteStarted.set(null);
        requestCompleteRelease.set(null);
        blockingServerCall.set(null);
        cancellingServerAllocator.allocated.clear();
        leakServerAllocator.allocated.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void requestIsDeserializedOnBlockingTaskExecutor(boolean unary) throws Exception {
        final ServiceRequestContext ctx = call(blockingServer, unary);

        assertThat(requestParseCount).hasPositiveValue();
        final Thread parseThread = requestParseThread.get();
        assertThat(parseThread).isNotNull();
        assertThat(ctx.eventLoop().inEventLoop(parseThread)).isFalse();
        assertThat(parseThread).isSameAs(handlerThread.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void requestIsDeserializedOnEventLoopWithoutBlockingTaskExecutor(boolean unary) throws Exception {
        final ServiceRequestContext ctx = call(eventLoopServer, unary);

        assertThat(requestParseCount).hasPositiveValue();
        final Thread parseThread = requestParseThread.get();
        assertThat(parseThread).isNotNull();
        assertThat(ctx.eventLoop().inEventLoop(parseThread)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void requestIsNotDeserializedAfterCancellation(boolean unary) throws Exception {
        try (ClientFactory factory = ClientFactory.builder().build()) {
            assertThatThrownBy(() -> call(cancellingServer, factory, unary))
                    .isInstanceOf(StatusRuntimeException.class);
        }

        // The request body buffers must be released even though the deserialization task ran after
        // the cancellation ...
        await().untilAsserted(() -> assertThat(cancellingServerAllocator.allocated)
                .allSatisfy(buf -> assertThat(buf.refCnt()).isZero()));
        // ... and the message must not have been deserialized.
        assertThat(requestParseCount).hasValue(0);
    }

    @Test
    void halfCloseIsNotInvokedAfterRequestDeserializationFails() throws Exception {
        final CountDownLatch parseStarted = new CountDownLatch(1);
        final CountDownLatch parseRelease = new CountDownLatch(1);
        final CountDownLatch completeStarted = new CountDownLatch(1);
        final CountDownLatch completeRelease = new CountDownLatch(1);
        requestParseStarted.set(parseStarted);
        requestParseRelease.set(parseRelease);
        requestParseFailure.set(Status.INTERNAL.withDescription("request deserialization failed")
                                               .asRuntimeException());
        requestCompleteStarted.set(completeStarted);
        requestCompleteRelease.set(completeRelease);

        try (ClientFactory factory = ClientFactory.builder().build()) {
            final TestServiceStub client =
                    GrpcClients.builder(blockingServer.httpUri())
                               .factory(factory)
                               .build(TestServiceStub.class);
            final CompletableFuture<Void> completion = new CompletableFuture<>();
            final StreamObserver<StreamingOutputCallRequest> requestObserver =
                    client.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
                        @Override
                        public void onNext(StreamingOutputCallResponse value) {}

                        @Override
                        public void onError(Throwable t) {
                            completion.completeExceptionally(t);
                        }

                        @Override
                        public void onCompleted() {
                            completion.complete(null);
                        }
                    });
            requestObserver.onNext(BIDI_REQUEST);
            assertThat(parseStarted.await(10, TimeUnit.SECONDS)).isTrue();
            requestObserver.onCompleted();
            // Wait until onRequestComplete() queues invokeHalfClose(), and then keep the event loop blocked
            // so that it cannot process the close requested by the failing deserialization task.
            assertThat(completeStarted.await(10, TimeUnit.SECONDS)).isTrue();

            final ServerCall<?, ?> call = blockingServerCall.get();
            assertThat(call).isInstanceOf(AbstractServerCall.class);
            final CountDownLatch blockingTasksDrained = new CountDownLatch(1);
            ((AbstractServerCall<?, ?>) call).blockingExecutor().execute(blockingTasksDrained::countDown);
            parseRelease.countDown();
            assertThat(blockingTasksDrained.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(requestHalfClosed.get()).isNotDone();

            completeRelease.countDown();
            assertThatThrownBy(() -> completion.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, cause ->
                            assertThat(cause.getCause())
                                    .isInstanceOfSatisfying(StatusRuntimeException.class, statusCause ->
                                            assertThat(statusCause.getStatus().getCode())
                                                    .isEqualTo(Status.Code.INTERNAL)));
        } finally {
            parseRelease.countDown();
            completeRelease.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void responseIsSerializedOnCallerThreadWithBlockingTaskExecutor(boolean unary) throws Exception {
        call(blockingServer, unary);

        assertThat(responseStreamThread.get()).isNotNull()
                                              .extracting(Thread::getName)
                                              .isEqualTo(CALLER_THREAD_NAME);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void responseIsSerializedOnCallerThreadWithoutBlockingTaskExecutor(boolean unary) throws Exception {
        call(eventLoopServer, unary);

        assertThat(responseStreamThread.get()).isNotNull()
                                              .extracting(Thread::getName)
                                              .isEqualTo(CALLER_THREAD_NAME);
    }

    @Test
    void compressionIsNegotiatedBeforeResponseIsSerialized() {
        // The client does not accept gzip, so the server must not compress the response even though the
        // service requested it. The service serializes the response before the event loop processes
        // `sendHeaders()`, so the negotiation must happen on the caller's thread.
        final TestServiceBlockingStub client =
                GrpcClients.builder(compressingServer.httpUri())
                           .decompressorRegistry(DecompressorRegistry.emptyInstance()
                                                                     .with(Codec.Identity.NONE, false))
                           .build(TestServiceBlockingStub.class);

        assertThat(client.unaryCall(UNARY_REQUEST)).isEqualTo(UNARY_RESPONSE);
    }

    @Test
    void unaryResponseIsReleasedWhenCallFailsAfterSendMessage() throws Exception {
        try (ClientFactory factory = ClientFactory.builder().build()) {
            assertThatThrownBy(() -> call(leakServer, factory, true))
                    .isInstanceOf(StatusRuntimeException.class);
        }

        assertThat(responseStreamThread.get()).isNotNull();
        await().untilAsserted(() -> assertThat(leakServerAllocator.allocated)
                .allSatisfy(buf -> assertThat(buf.refCnt()).isZero()));
    }

    @Test
    void streamingResponseIsReleasedWhenSentAfterCancellation() throws Exception {
        try (ClientFactory factory = ClientFactory.builder().build()) {
            final TestServiceStub client =
                    GrpcClients.builder(leakServer.httpUri())
                               .factory(factory)
                               .build(TestServiceStub.class);
            final CompletableFuture<Void> completion = new CompletableFuture<>();
            final ClientCallStreamObserver<StreamingOutputCallRequest> requestObserver =
                    (ClientCallStreamObserver<StreamingOutputCallRequest>) client.fullDuplexCall(
                            new StreamObserver<StreamingOutputCallResponse>() {
                                @Override
                                public void onNext(StreamingOutputCallResponse value) {}

                                @Override
                                public void onError(Throwable t) {
                                    completion.completeExceptionally(t);
                                }

                                @Override
                                public void onCompleted() {
                                    completion.complete(null);
                                }
                            });
            requestObserver.onNext(BIDI_REQUEST);
            requestReceived.get().get(10, TimeUnit.SECONDS);
            requestObserver.cancel("cancelled by the test", null);
            assertThatThrownBy(() -> completion.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(StatusRuntimeException.class);

            // Wait until the service sent the message after the cancellation.
            await().until(() -> responseStreamThread.get() != null);
        }

        await().untilAsserted(() -> assertThat(leakServerAllocator.allocated)
                .allSatisfy(buf -> assertThat(buf.refCnt()).isZero()));
    }

    private static ServiceRequestContext call(ServerExtension server, boolean unary) throws Exception {
        return call(server, ClientFactory.ofDefault(), unary);
    }

    private static ServiceRequestContext call(ServerExtension server, ClientFactory factory,
                                              boolean unary) throws Exception {
        if (unary) {
            final TestServiceBlockingStub client =
                    GrpcClients.builder(server.httpUri())
                               .factory(factory)
                               .build(TestServiceBlockingStub.class);
            client.unaryCall(UNARY_REQUEST);
        } else {
            final TestServiceStub client =
                    GrpcClients.builder(server.httpUri())
                               .factory(factory)
                               .build(TestServiceStub.class);
            final CompletableFuture<Void> completion = new CompletableFuture<>();
            final StreamObserver<StreamingOutputCallRequest> requestObserver =
                    client.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
                        @Override
                        public void onNext(StreamingOutputCallResponse value) {}

                        @Override
                        public void onError(Throwable t) {
                            completion.completeExceptionally(t);
                        }

                        @Override
                        public void onCompleted() {
                            completion.complete(null);
                        }
                    });
            requestObserver.onNext(BIDI_REQUEST);
            requestObserver.onNext(BIDI_REQUEST);
            requestObserver.onCompleted();
            try {
                completion.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw (Exception) e.getCause();
            }
        }
        return server.requestContextCaptor().take();
    }

    /**
     * Binds {@code UnaryCall} and {@code FullDuplexCall} with marshallers which record the threads that
     * perform the (de)serialization, and handlers which record the thread that receives the request and
     * send the responses from the {@link #caller} thread.
     */
    private static ServerServiceDefinition recordingService() {
        final MethodDescriptor<SimpleRequest, SimpleResponse> unaryMethod =
                recording(TestServiceGrpc.getUnaryCallMethod());
        final MethodDescriptor<StreamingOutputCallRequest, StreamingOutputCallResponse> bidiMethod =
                recording(TestServiceGrpc.getFullDuplexCallMethod());

        return ServerServiceDefinition
                .builder(TestServiceGrpc.SERVICE_NAME)
                .addMethod(unaryMethod, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    handlerThread.set(Thread.currentThread());
                    caller.execute(() -> {
                        responseObserver.onNext(UNARY_RESPONSE);
                        responseObserver.onCompleted();
                    });
                }))
                .addMethod(bidiMethod, ServerCalls.asyncBidiStreamingCall(
                        responseObserver -> new StreamObserver<StreamingOutputCallRequest>() {
                            @Override
                            public void onNext(StreamingOutputCallRequest value) {
                                handlerThread.set(Thread.currentThread());
                                caller.execute(() -> responseObserver.onNext(BIDI_RESPONSE));
                            }

                            @Override
                            public void onError(Throwable t) {}

                            @Override
                            public void onCompleted() {
                                requestHalfClosed.get().complete(null);
                                caller.execute(responseObserver::onCompleted);
                            }
                        }))
                .build();
    }

    private static <I, O> MethodDescriptor<I, O> recording(MethodDescriptor<I, O> method) {
        return method.toBuilder()
                     .setRequestMarshaller(new RecordingMarshaller<>(method.getRequestMarshaller()))
                     .setResponseMarshaller(new RecordingMarshaller<>(method.getResponseMarshaller()))
                     .build();
    }

    /**
     * Records the thread which calls {@link #parse(InputStream)} (request deserialization on the server)
     * and {@link #stream(Object)} (response serialization on the server).
     */
    private static final class RecordingMarshaller<T> implements PrototypeMarshaller<T> {

        private final PrototypeMarshaller<T> delegate;

        RecordingMarshaller(Marshaller<T> delegate) {
            this.delegate = (PrototypeMarshaller<T>) delegate;
        }

        @Nullable
        @Override
        public T getMessagePrototype() {
            return delegate.getMessagePrototype();
        }

        @Override
        public Class<T> getMessageClass() {
            return delegate.getMessageClass();
        }

        @Override
        public InputStream stream(T value) {
            responseStreamThread.set(Thread.currentThread());
            return delegate.stream(value);
        }

        @Override
        public T parse(InputStream stream) {
            requestParseThread.set(Thread.currentThread());
            requestParseCount.incrementAndGet();
            final CountDownLatch parseStarted = requestParseStarted.get();
            if (parseStarted != null) {
                parseStarted.countDown();
                final CountDownLatch parseRelease = requestParseRelease.get();
                assert parseRelease != null;
                BlockingUtils.blockingRun(() -> parseRelease.await(10, TimeUnit.SECONDS));
            }
            final RuntimeException failure = requestParseFailure.get();
            if (failure != null) {
                throw failure;
            }
            return delegate.parse(stream);
        }
    }

    /**
     * An unpooled allocator which remembers every buffer it allocated, so that a test can verify that all
     * of them were released. Unpooled buffers are not recycled, so a released buffer stays at zero.
     */
    private static final class TrackingAllocator extends AbstractByteBufAllocator {

        private final UnpooledByteBufAllocator delegate = new UnpooledByteBufAllocator(true);
        final Queue<ByteBuf> allocated = new ConcurrentLinkedQueue<>();

        TrackingAllocator() {
            super(true);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            return track(delegate.heapBuffer(initialCapacity, maxCapacity));
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return track(delegate.directBuffer(initialCapacity, maxCapacity));
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        private ByteBuf track(ByteBuf buf) {
            allocated.add(buf);
            return buf;
        }
    }
}
