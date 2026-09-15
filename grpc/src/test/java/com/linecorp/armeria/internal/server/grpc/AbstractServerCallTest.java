/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.armeria.internal.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.FilteredHttpRequest;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBuf;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.StreamingInputCallRequest;
import testing.grpc.Messages.StreamingInputCallResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class AbstractServerCallTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final AtomicReference<ServerCall<?, ?>> serverCallCaptor = new AtomicReference<>();
            final GrpcService grpcService =
                    GrpcService.builder()
                               .useBlockingTaskExecutor(true)
                               .unsafeWrapRequestBuffers(true)
                               .useClientTimeoutHeader(false)
                               .addService(ServerInterceptors.intercept(
                                       new FooTestServiceImpl(),
                                       new ServerInterceptor() {

                                           @Override
                                           public <T, U> Listener<T> interceptCall(
                                                   ServerCall<T, U> call, Metadata headers,
                                                   ServerCallHandler<T, U> next) {
                                               serverCallCaptor.set(call);
                                               return next.startCall(call, headers);
                                           }
                                       }))
                               .build();
            sb.service(grpcService);
            sb.decorator((delegate, ctx, req) -> {
                final FilteredHttpRequest newReq = new FilteredHttpRequest(req) {
                    @Override
                    protected void beforeSubscribe(Subscriber<? super HttpObject> subscriber,
                                                   Subscription subscription) {
                        // This is called right before
                        // callExecutor.execute(() -> invokeOnMessage(request, endOfStream));
                        // in AbstractServerCall.
                        final ServerCall<?, ?> serverCall = serverCallCaptor.get();
                        assertThat(serverCall).isInstanceOf(AbstractServerCall.class);
                        ((AbstractServerCall<?, ?>) serverCall).callExecutor.execute(() -> {
                            // invokeOnMessage is not called until the request is cancelled.
                            await().until(serverCall::isCancelled);
                            // The request message was deframed and its buffer stored in the meantime.
                            await().until(() -> {
                                final IdentityHashMap<Object, ByteBuf> buffers =
                                        ctx.attr(GrpcUnsafeBufferUtil.BUFFERS);
                                return buffers != null && !buffers.isEmpty();
                            });
                            storedBuffer.set(ctx.attr(GrpcUnsafeBufferUtil.BUFFERS).values().iterator().next());
                            // Now, AbstractServerCall.invokeOnMessage() is called and it doesn't call
                            // listener.onMessage() because the request is cancelled.
                        });
                    }

                    @Override
                    protected HttpObject filter(HttpObject obj) {
                        return obj;
                    }
                };
                ctx.updateRequest(newReq);
                ctxCaptor.set(ctx);
                return delegate.serve(ctx, newReq);
            });
            sb.requestTimeoutMillis(100);
        }
    };

    private static final AtomicBoolean isOnNextCalled = new AtomicBoolean();
    private static final AtomicReference<ServiceRequestContext> ctxCaptor = new AtomicReference<>();
    private static final AtomicReference<ByteBuf> storedBuffer = new AtomicReference<>();

    @Test
    void onMessageIsNotCalledWhenRequestCancelled() throws InterruptedException {
        final TestServiceStub testServiceStub = GrpcClients.newClient(server.httpUri(), TestServiceStub.class);
        final CompletableFuture<Throwable> future = new CompletableFuture<>();
        final StreamObserver<StreamingInputCallRequest> streamingInputCallRequestStreamObserver =
                testServiceStub.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
                    @Override
                    public void onNext(StreamingInputCallResponse value) {}

                    @Override
                    public void onError(Throwable t) {
                        future.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        streamingInputCallRequestStreamObserver.onNext(
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFromUtf8("payload")))
                                         .build());
        assertThatThrownBy(future::get).hasCauseInstanceOf(StatusRuntimeException.class)
                                       .hasMessageContaining("CANCELLED");
        // Sleep additional 1 second to make sure that the onNext() is not called.
        Thread.sleep(1000);
        assertThat(isOnNextCalled).isFalse();

        // The skipped message must release the buffer it wrapped, exactly once.
        await().untilAsserted(() -> {
            assertThat(storedBuffer.get()).isNotNull();
            assertThat(storedBuffer.get().refCnt()).isZero();
            assertThat(ctxCaptor.get().attr(GrpcUnsafeBufferUtil.BUFFERS)).isEmpty();
        });
    }

    private static class FooTestServiceImpl extends TestServiceGrpc.TestServiceImplBase {

        @Override
        public StreamObserver<StreamingInputCallRequest> streamingInputCall(
                StreamObserver<StreamingInputCallResponse> responseObserver) {
            return new StreamObserver<StreamingInputCallRequest>() {
                @Override
                public void onNext(StreamingInputCallRequest value) {
                    // If this method is called that means listener.onMessage() in AbstractServerCall is called.
                    isOnNextCalled.set(true);
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };
        }
    }
}
