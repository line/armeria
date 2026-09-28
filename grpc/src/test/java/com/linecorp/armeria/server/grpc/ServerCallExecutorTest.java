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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.internal.common.grpc.GrpcTestUtil;
import com.linecorp.armeria.internal.common.grpc.InternalGrpcExceptionHandler;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall.Listener;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoop;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;

@Timeout(10)
class ServerCallExecutorTest {

    @Test
    void queuedEventLoopRequestsAreReleasedAfterCancellation() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"));
        final HttpResponseWriter response = HttpResponse.streaming();
        final StreamingServerCall<SimpleRequest, SimpleResponse> call =
                newServerCall(ctx, response, GrpcExceptionHandlerFunction.of());
        final List<String> events = new ArrayList<>();
        final ByteBuf message = GrpcTestUtil.requestByteBuf();
        try {
            ctx.eventLoop().submit(() -> {
                call.setListener(new RecordingListener(events));
                call.onNext(new DeframedMessage(message, 0));
                call.transportReportStatus(Status.CANCELLED, new Metadata());
            }).get(5, TimeUnit.SECONDS);
            drain(call);
            assertThat(events).containsExactly("ready", "cancel");
            assertThat(message.refCnt()).isZero();
        } finally {
            response.abort();
        }
    }

    @Test
    void queuedEventLoopCallbacksAreSkippedWhileHandlingDeserializationFailure() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"));
        final HttpResponseWriter response = HttpResponse.streaming();
        final CompletableFuture<@Nullable Status> errorStatus = new CompletableFuture<>();
        final GrpcExceptionHandlerFunction handler = new GrpcExceptionHandlerFunction() {
            @Override
            public Status apply(RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
                return Status.INTERNAL;
            }

            @Override
            public CompletableFuture<@Nullable Status> applyAsync(
                    RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
                return errorStatus;
            }
        };
        final StreamingServerCall<SimpleRequest, SimpleResponse> call = newServerCall(ctx, response, handler);
        final List<String> events = new ArrayList<>();
        final ByteBuf malformed = Unpooled.buffer().writeByte(0xff);
        final ByteBuf next = GrpcTestUtil.requestByteBuf();
        try {
            ctx.eventLoop().submit(() -> {
                call.setListener(new RecordingListener(events));
                call.onNext(new DeframedMessage(malformed, 0));
                call.onNext(new DeframedMessage(next, 0));
                call.onComplete();
            }).get(5, TimeUnit.SECONDS);
            drain(call);
            // The async error handler has not closed the call yet.
            // Already queued callbacks must still be skipped.
            assertThat(call.isCloseCalled()).isFalse();
            assertThat(events).containsExactly("ready");
            assertThat(malformed.refCnt()).isZero();
            assertThat(next.refCnt()).isZero();
        } finally {
            errorStatus.complete(Status.INTERNAL);
            ctx.eventLoop().submit(() -> {}).get(5, TimeUnit.SECONDS);
            drain(call);
            response.abort();
        }
    }

    @Test
    void rejectedEventLoopRequestIsReleased() throws Exception {
        final DefaultEventLoop eventLoop = new DefaultEventLoop();
        final ServiceRequestContext ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/"))
                                                               .eventLoop(eventLoop).build();
        final HttpResponseWriter response = HttpResponse.streaming();
        final StreamingServerCall<SimpleRequest, SimpleResponse> call =
                newServerCall(ctx, response, GrpcExceptionHandlerFunction.of());
        final ByteBuf message = GrpcTestUtil.requestByteBuf();
        try {
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
            call.onNext(new DeframedMessage(message, 0));
            assertThat(message.refCnt()).isZero();
        } finally {
            if (message.refCnt() > 0) {
                message.release();
            }
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).sync();
            response.abort();
        }
    }

    private static void drain(StreamingServerCall<?, ?> call) throws Exception {
        CompletableFuture.runAsync(() -> {}, call.callExecutor()).get(5, TimeUnit.SECONDS);
    }

    private static StreamingServerCall<SimpleRequest, SimpleResponse> newServerCall(
            ServiceRequestContext ctx, HttpResponseWriter response, GrpcExceptionHandlerFunction handler) {
        final MethodDescriptor<SimpleRequest, SimpleResponse> method =
                TestServiceGrpc.getUnaryCallMethod().toBuilder()
                               .setType(MethodDescriptor.MethodType.BIDI_STREAMING).build();
        return new StreamingServerCall<>(
                ctx.request(), method, "BidiCall", CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(), response, 1024, 1024, ctx,
                GrpcSerializationFormats.PROTO, null, false, ResponseHeaders.of(200),
                new InternalGrpcExceptionHandler(handler), null, false, false);
    }

    private static final class RecordingListener extends Listener<SimpleRequest> {
        private final List<String> events;

        RecordingListener(List<String> events) {
            this.events = events;
        }

        @Override
        public void onReady() {
            events.add("ready");
        }

        @Override
        public void onMessage(SimpleRequest message) {
            events.add("message");
        }

        @Override
        public void onHalfClose() {
            events.add("halfClose");
        }

        @Override
        public void onCancel() {
            events.add("cancel");
        }
    }
}
