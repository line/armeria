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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.FilteredHttpRequest;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.grpc.AbstractServerCall;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

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
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelOption;
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

    private static final AtomicReference<Thread> requestParseThread = new AtomicReference<>();
    private static final AtomicInteger requestParseCount = new AtomicInteger();
    private static final AtomicReference<Thread> handlerThread = new AtomicReference<>();

    private static final TrackingAllocator cancellingServerAllocator = new TrackingAllocator();

    // Non-empty messages, because an empty message is not deserialized through the marshaller.
    private static final SimpleRequest UNARY_REQUEST =
            SimpleRequest.newBuilder().setResponseSize(1).build();
    private static final StreamingOutputCallRequest BIDI_REQUEST =
            StreamingOutputCallRequest.newBuilder()
                                      .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                      .build();

    @RegisterExtension
    static final ServerExtension blockingServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(recordingService())
                                  .useMethodMarshaller(true)
                                  .useBlockingTaskExecutor(true)
                                  .build());
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

    @BeforeEach
    void reset() {
        requestParseThread.set(null);
        requestParseCount.set(0);
        handlerThread.set(null);
        cancellingServerAllocator.allocated.clear();
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
     * Binds {@code UnaryCall} and {@code FullDuplexCall} with marshallers which record the thread that
     * performs the deserialization, and handlers which record the thread that receives the request.
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
                    responseObserver.onNext(SimpleResponse.getDefaultInstance());
                    responseObserver.onCompleted();
                }))
                .addMethod(bidiMethod, ServerCalls.asyncBidiStreamingCall(
                        responseObserver -> new StreamObserver<StreamingOutputCallRequest>() {
                            @Override
                            public void onNext(StreamingOutputCallRequest value) {
                                handlerThread.set(Thread.currentThread());
                                responseObserver.onNext(StreamingOutputCallResponse.getDefaultInstance());
                            }

                            @Override
                            public void onError(Throwable t) {}

                            @Override
                            public void onCompleted() {
                                responseObserver.onCompleted();
                            }
                        }))
                .build();
    }

    private static <I, O> MethodDescriptor<I, O> recording(MethodDescriptor<I, O> method) {
        return method.toBuilder()
                     .setRequestMarshaller(new RecordingMarshaller<>(method.getRequestMarshaller()))
                     .build();
    }

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
            return delegate.stream(value);
        }

        @Override
        public T parse(InputStream stream) {
            requestParseThread.set(Thread.currentThread());
            requestParseCount.incrementAndGet();
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
