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

import static com.linecorp.armeria.internal.common.grpc.TestServiceImpl.EXTRA_HEADER_KEY;
import static com.linecorp.armeria.internal.common.grpc.TestServiceImpl.EXTRA_HEADER_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.internal.common.grpc.DefaultJsonMarshaller;
import com.linecorp.armeria.internal.common.grpc.GrpcTestUtil;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.util.AsciiString;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc;

// TODO(anuraag): Currently only grpc-protobuf has been published so we only test proto here.
// Once grpc-thrift is published, add tests for thrift stubs which will not go through the
// optimized protobuf marshalling paths.
class StreamingServerCallTest {

    private static final int MAX_MESSAGE_BYTES = 1024;
    private static final AsciiString EXTRA_HEADER_NAME1 = HttpHeaderNames.of("extra-header-1");
    private static final Key<String> EXTRA_HEADER_KEY1 = Key.of(EXTRA_HEADER_NAME1.toString(),
                                                                Metadata.ASCII_STRING_MARSHALLER);

    @Mock
    private HttpResponseWriter res;

    @Mock
    private ServerCall.Listener<SimpleRequest> listener;

    private ServiceRequestContext ctx;

    @Mock
    private IdentityHashMap<Object, ByteBuf> buffersAttr;

    private StreamingServerCall<SimpleRequest, SimpleResponse> call;

    private CompletableFuture<Void> completionFuture;

    @BeforeEach
    void setUp() {
        completionFuture = new CompletableFuture<>();
        when(res.whenComplete()).thenReturn(completionFuture);

        ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/"))
                                   .eventLoop(EventLoopGroups.directEventLoop())
                                   .build();

        call = newServerCall(res, false);
        call.setListener(listener);

        ctx.setAttr(GrpcUnsafeBufferUtil.BUFFERS, buffersAttr);
    }

    @AfterEach
    void tearDown() {
        if (!call.isCloseCalled()) {
            call.close(Status.OK, new Metadata());
        }
    }

    @Test
    void messageReadAfterClose_byteBuf() {
        call.close(Status.ABORTED, new Metadata());

        // messageRead is always called from the event loop.
        call.onNext(new DeframedMessage(GrpcTestUtil.requestByteBuf(), 0));
        verify(listener, never()).onMessage(any());
    }

    @Test
    void messageRead_notWrappedByteBuf() {
        final ByteBuf buf = GrpcTestUtil.requestByteBuf();
        call.onNext(new DeframedMessage(buf, 0));

        verifyNoMoreInteractions(buffersAttr);
    }

    @Test
    void messageRead_wrappedByteBuf() {
        tearDown();

        call = newServerCall(res, true);

        call.setListener(mock(Listener.class));
        final ByteBuf buf = GrpcTestUtil.requestByteBuf();
        call.onNext(new DeframedMessage(buf, 0));

        verify(buffersAttr).put(any(), same(buf));
    }

    @Test
    void messageReadAfterClose_stream() {
        call.close(Status.ABORTED, new Metadata());

        call.onNext(new DeframedMessage(new ByteBufInputStream(GrpcTestUtil.requestByteBuf(), true),
                                        0));

        verify(listener, never()).onMessage(any());
    }

    @Test
    void readyOnStart() {
        assertThat(call.isReady()).isTrue();
        call.close(Status.OK, new Metadata());
    }

    @Test
    void notReadyAfterClose() {
        assertThat(call.isReady()).isTrue();
        call.close(Status.OK, new Metadata());
        await().untilAsserted(() -> assertThat(call.isReady()).isFalse());
    }

    @Test
    void closedIfCancelled() {
        assertThat(call.isCancelled()).isFalse();
        completionFuture.completeExceptionally(ClosedSessionException.get());
        await().untilAsserted(() -> assertThat(call.isCancelled()).isTrue());
    }

    @Test
    void deferResponseHeaders() {
        final HttpResponseWriter response = HttpResponse.streaming();
        call = newServerCall(response, false);

        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        final List<HttpObject> received = new ArrayList<>();
        response.subscribe(new Subscriber<HttpObject>() {

            @Override
            public void onSubscribe(Subscription s) {
                subscriptionRef.set(s);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                received.add(httpObject);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        }, ctx.eventLoop());

        final Metadata metadata = new Metadata();
        metadata.put(EXTRA_HEADER_KEY, "dog");
        call.sendHeaders(metadata);
        subscriptionRef.get().request(1);
        // Headers are deferred until the first response is received.
        assertThat(received).isEmpty();

        call.sendMessage(SimpleResponse.getDefaultInstance());
        assertThat(received).hasSize(1);
        final ResponseHeaders headers = (ResponseHeaders) received.get(0);
        assertThat(headers.get(EXTRA_HEADER_NAME)).isEqualTo("dog");

        subscriptionRef.get().request(1);
        assertThat(received).hasSize(2);
        assertThat(received.get(1)).isInstanceOf(HttpData.class);
    }

    @Test
    void deferResponseHeaders_unary_nonResponseMessage() {
        final HttpResponseWriter response = HttpResponse.streaming();
        call = newServerCall(response, false);

        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        final List<HttpObject> received = new ArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean();
        response.subscribe(new Subscriber<HttpObject>() {

            @Override
            public void onSubscribe(Subscription s) {
                subscriptionRef.set(s);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                received.add(httpObject);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }, ctx.eventLoop());

        final Metadata metadata = new Metadata();
        metadata.put(EXTRA_HEADER_KEY, "dog");
        call.sendHeaders(metadata);
        subscriptionRef.get().request(1);
        // Headers are deferred until the first response is received.
        assertThat(received).isEmpty();

        final Metadata closingMetadata = new Metadata();
        closingMetadata.put(EXTRA_HEADER_KEY1, "cat");
        call.close(Status.INTERNAL, closingMetadata);

        // The headers will be ignored and the response will complete with Trailers-Only.
        assertThat(received).hasSize(1);
        final ResponseHeaders headers = (ResponseHeaders) received.get(0);
        assertThat(headers.get(EXTRA_HEADER_NAME)).isNull();
        assertThat(headers.get(EXTRA_HEADER_NAME1)).isEqualTo("cat");
        assertThat(completed).isTrue();
    }

    @Test
    void deferResponseHeaders_streaming_nonResponseMessage() {
        final HttpResponseWriter response = HttpResponse.streaming();
        final StreamingServerCall<StreamingOutputCallRequest, StreamingOutputCallResponse> call =
                new StreamingServerCall<>(
                        HttpRequest.of(HttpMethod.GET, "/"),
                        TestServiceGrpc.getStreamingOutputCallMethod(),
                        TestServiceGrpc.getStreamingOutputCallMethod().getBareMethodName(),
                        CompressorRegistry.getDefaultInstance(),
                        DecompressorRegistry.getDefaultInstance(),
                        response,
                        MAX_MESSAGE_BYTES,
                        MAX_MESSAGE_BYTES,
                        ctx,
                        GrpcSerializationFormats.PROTO,
                        new DefaultJsonMarshaller(MessageMarshaller.builder().build()),
                        false,
                        ResponseHeaders.builder(HttpStatus.OK)
                                       .contentType(GrpcSerializationFormats.PROTO.mediaType())
                                       .build(),
                        /* exceptionMappings */ null,
                        /* blockingExecutor */ null,
                        false,
                        false);

        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        final List<HttpObject> received = new ArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean();
        response.subscribe(new Subscriber<HttpObject>() {

            @Override
            public void onSubscribe(Subscription s) {
                subscriptionRef.set(s);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                received.add(httpObject);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }, ctx.eventLoop());

        final Metadata metadata = new Metadata();
        metadata.put(EXTRA_HEADER_KEY, "dog");
        call.sendHeaders(metadata);
        subscriptionRef.get().request(1);
        // Headers are deferred until the first response is received.
        assertThat(received).isEmpty();

        final Metadata closingMetadata = new Metadata();
        closingMetadata.put(EXTRA_HEADER_KEY1, "cat");
        // A stream call is successfully completed with no response message.
        call.close(Status.OK, closingMetadata);
        subscriptionRef.get().request(1);

        // The headers and trailers will be sent.
        assertThat(received).hasSize(2);
        final ResponseHeaders headers = (ResponseHeaders) received.get(0);
        assertThat(headers.get(EXTRA_HEADER_NAME)).isEqualTo("dog");
        assertThat(headers.get(EXTRA_HEADER_NAME)).isEqualTo("dog");
        final HttpHeaders trailers = (HttpHeaders) received.get(1);
        assertThat(trailers.get(EXTRA_HEADER_NAME1)).isEqualTo("cat");
        assertThat(completed).isTrue();
    }

    private StreamingServerCall<SimpleRequest, SimpleResponse> newServerCall(HttpResponseWriter response,
                                                                             boolean unsafeWrapRequestBuffers) {
        return new StreamingServerCall<>(
                HttpRequest.of(HttpMethod.GET, "/"),
                TestServiceGrpc.getUnaryCallMethod(),
                TestServiceGrpc.getUnaryCallMethod().getBareMethodName(),
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                response,
                MAX_MESSAGE_BYTES,
                MAX_MESSAGE_BYTES,
                ctx,
                GrpcSerializationFormats.PROTO,
                new DefaultJsonMarshaller(MessageMarshaller.builder().build()),
                unsafeWrapRequestBuffers,
                ResponseHeaders.builder(HttpStatus.OK)
                               .contentType(GrpcSerializationFormats.PROTO.mediaType())
                               .build(),
                /* exceptionMappings */ null,
                /* blockingExecutor */ null,
                false,
                false);
    }
}
