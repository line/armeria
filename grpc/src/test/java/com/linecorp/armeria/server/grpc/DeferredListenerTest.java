/*
 * Copyright 2023 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.grpc.InternalGrpcExceptionHandler;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ServerCall;
import io.netty.channel.EventLoop;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;

class DeferredListenerTest {

    @Test
    void shouldHaveRequestContextInThread() {
        assertThatThrownBy(() -> new DeferredListener<>(mock(ServerCall.class), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot use %s with a non-Armeria gRPC server",
                            AsyncServerInterceptor.class.getName());
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void shouldLazilyExecuteCallbacks(boolean wrap) {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final UnaryServerCall<SimpleRequest, SimpleResponse> armeriaCall = newServerCall(eventLoop, null);
        ServerCall<SimpleRequest, SimpleResponse> serverCall = armeriaCall;
        if (wrap) {
            serverCall = new SimpleForwardingServerCall<SimpleRequest, SimpleResponse>(serverCall) {};
        }
        assertListenerEvents(serverCall, armeriaCall.callExecutor());

        final Executor blockingExecutor =
                MoreExecutors.newSequentialExecutor(CommonPools.blockingTaskExecutor());
        final UnaryServerCall<SimpleRequest, SimpleResponse> blockingServerCall =
                newServerCall(eventLoop, blockingExecutor);
        assertListenerEvents(blockingServerCall, blockingServerCall.callExecutor());
    }

    private static void assertListenerEvents(ServerCall<SimpleRequest, SimpleResponse> serverCall,
                                             Executor executor) {
        final TestListener testListener = new TestListener();
        final CompletableFuture<ServerCall.Listener<SimpleRequest>> future = new CompletableFuture<>();
        final DeferredListener<SimpleRequest> listener = new DeferredListener<>(serverCall, future);
        executeAndAwait(executor, () -> {
            listener.onMessage(null);
            listener.onReady();
            listener.onHalfClose();
        });

        assertThat(testListener.events).isEmpty();
        future.complete(testListener);
        await().untilAsserted(() -> {
            assertThat(testListener.events).containsExactly("onMessage", "onReady", "onHalfClose");
        });

        executeAndAwait(executor, listener::onComplete);
        assertThat(testListener.events)
                .containsExactly("onMessage", "onReady", "onHalfClose", "onComplete");
        executeAndAwait(executor, listener::onCancel);
        assertThat(testListener.events)
                .containsExactly("onMessage", "onReady", "onHalfClose", "onComplete", "onCancel");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void listenerReadinessDoesNotReorderCallbacks(boolean blocking) {
        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final UnaryServerCall<SimpleRequest, SimpleResponse> call =
                newServerCall(eventLoop, blocking ? CommonPools.blockingTaskExecutor() : null);
        final CompletableFuture<ServerCall.Listener<SimpleRequest>> future = new CompletableFuture<>();
        final DeferredListener<SimpleRequest> listener = new DeferredListener<>(call, future);
        final TestListener delegate = new TestListener();
        executeAndAwait(call.callExecutor(), () -> {
            call.callExecutor().execute(() -> listener.onMessage(null));
            future.complete(delegate);
            call.callExecutor().execute(listener::onHalfClose);
        });
        executeAndAwait(call.callExecutor(), () -> {});
        assertThat(delegate.events).containsExactly("onMessage", "onHalfClose");
    }

    @Test
    void readyListenerCallbacksFromOutsideExecutorWaitForQueuedCallbacks() {
        final UnaryServerCall<SimpleRequest, SimpleResponse> call =
                newServerCall(CommonPools.workerGroup().next(), null);
        final TestListener delegate = new TestListener();
        final DeferredListener<SimpleRequest> listener =
                new DeferredListener<>(call, UnmodifiableFuture.completedFuture(delegate));
        executeAndAwait(call.callExecutor(), () -> {});
        listener.onMessage(null);
        listener.onHalfClose();
        executeAndAwait(call.callExecutor(), () -> {});
        assertThat(delegate.events).containsExactly("onMessage", "onHalfClose");
    }

    @Test
    void readyListenerFailureFromOutsideExecutorClosesCall() {
        final UnaryServerCall<SimpleRequest, SimpleResponse> call =
                newServerCall(CommonPools.workerGroup().next(), null);
        final TestListener delegate = new TestListener() {
            @Override
            public void onMessage(SimpleRequest message) {
                throw new IllegalArgumentException("listener failure");
            }
        };
        final DeferredListener<SimpleRequest> listener =
                new DeferredListener<>(call, UnmodifiableFuture.completedFuture(delegate));
        executeAndAwait(call.callExecutor(), () -> call.setListener(listener));
        listener.onMessage(null);
        executeAndAwait(call.callExecutor(), () -> {});
        assertThat(call.isCloseCalled()).isTrue();
    }

    private static void executeAndAwait(Executor executor, Runnable task) {
        CompletableFuture.runAsync(task, executor).join();
    }

    private static UnaryServerCall<SimpleRequest, SimpleResponse> newServerCall(
            EventLoop eventLoop, @Nullable Executor blockingTaskExecutor) {
        final ServiceRequestContext ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/"))
                                                               .eventLoop(eventLoop)
                                                               .build();
        return new UnaryServerCall<>(ctx.request(), TestServiceGrpc.getUnaryCallMethod(), "UnaryCall",
                                     CompressorRegistry.getDefaultInstance(),
                                     DecompressorRegistry.getDefaultInstance(),
                                     HttpResponse.streaming(), new CompletableFuture<>(), 0, 0, ctx,
                                     GrpcSerializationFormats.PROTO, null, false,
                                     ResponseHeaders.of(200),
                                     new InternalGrpcExceptionHandler(GrpcExceptionHandlerFunction.of()),
                                     blockingTaskExecutor, false, false, false);
    }

    private static class TestListener extends ServerCall.Listener<SimpleRequest> {

        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onMessage(SimpleRequest message) {
            events.add("onMessage");
        }

        @Override
        public void onHalfClose() {
            events.add("onHalfClose");
        }

        @Override
        public void onCancel() {
            events.add("onCancel");
        }

        @Override
        public void onComplete() {
            events.add("onComplete");
        }

        @Override
        public void onReady() {
            events.add("onReady");
        }
    }
}
