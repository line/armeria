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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer.DeframedMessage;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.internal.common.grpc.DefaultJsonMarshaller;
import com.linecorp.armeria.internal.common.grpc.GrpcTestUtil;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit4.common.EventLoopRule;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;

// TODO(anuraag): Currently only grpc-protobuf has been published so we only test proto here.
// Once grpc-thrift is published, add tests for thrift stubs which will not go through the
// optimized protobuf marshalling paths.
public class ArmeriaServerCallTest {

    private static final int MAX_MESSAGE_BYTES = 1024;

    @ClassRule
    public static final EventLoopRule eventLoop = new EventLoopRule();

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private HttpResponseWriter res;

    @Mock
    private ServerCall.Listener<SimpleRequest> listener;

    @Mock
    private Subscription subscription;

    private ServiceRequestContext ctx;

    @Mock
    private IdentityHashMap<Object, ByteBuf> buffersAttr;

    private ArmeriaServerCall<SimpleRequest, SimpleResponse> call;

    private CompletableFuture<Void> completionFuture;

    @Before
    public void setUp() {
        completionFuture = new CompletableFuture<>();
        when(res.whenComplete()).thenReturn(completionFuture);

        ctx = ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/"))
                                   .eventLoop(EventLoopGroups.directEventLoop())
                                   .build();

        call = new ArmeriaServerCall<>(
                HttpHeaders.of(),
                TestServiceGrpc.getUnaryCallMethod(),
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                res,
                MAX_MESSAGE_BYTES,
                MAX_MESSAGE_BYTES,
                ctx,
                GrpcSerializationFormats.PROTO,
                new DefaultJsonMarshaller(MessageMarshaller.builder().build()),
                false,
                false,
                ResponseHeaders.builder(HttpStatus.OK)
                               .contentType(GrpcSerializationFormats.PROTO.mediaType())
                               .build());
        call.setListener(listener);
        call.messageReader().onSubscribe(subscription);

        ctx.setAttr(GrpcUnsafeBufferUtil.BUFFERS, buffersAttr);
    }

    @After
    public void tearDown() {
        call.messageReader().abort();
        if (!call.isCloseCalled()) {
            call.close(Status.OK, new Metadata());
        }
    }

    @Test
    public void messageReadAfterClose_byteBuf() {
        call.close(Status.ABORTED, new Metadata());

        // messageRead is always called from the event loop.
        call.onNext(new DeframedMessage(GrpcTestUtil.requestByteBuf(), 0));
        verify(listener, never()).onMessage(any());
    }

    @Test
    public void messageRead_notWrappedByteBuf() {
        final ByteBuf buf = GrpcTestUtil.requestByteBuf();
        call.onNext(new DeframedMessage(buf, 0));

        verifyNoMoreInteractions(buffersAttr);
    }

    @Test
    public void messageRead_wrappedByteBuf() {
        tearDown();

        call = new ArmeriaServerCall<>(
                HttpHeaders.of(),
                TestServiceGrpc.getUnaryCallMethod(),
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                res,
                MAX_MESSAGE_BYTES,
                MAX_MESSAGE_BYTES,
                ctx,
                GrpcSerializationFormats.PROTO,
                new DefaultJsonMarshaller(MessageMarshaller.builder().build()),
                true,
                false,
                ResponseHeaders.builder(HttpStatus.OK)
                               .contentType(GrpcSerializationFormats.PROTO.mediaType())
                               .build());

        final ByteBuf buf = GrpcTestUtil.requestByteBuf();
        call.onNext(new DeframedMessage(buf, 0));

        verify(buffersAttr).put(any(), same(buf));
    }

    @Test
    public void messageReadAfterClose_stream() {
        call.close(Status.ABORTED, new Metadata());

        call.onNext(new DeframedMessage(new ByteBufInputStream(GrpcTestUtil.requestByteBuf(), true),
                                        0));

        verify(listener, never()).onMessage(any());
    }

    @Test
    public void readyOnStart() {
        assertThat(call.isReady()).isTrue();
        call.messageReader().abort();
    }

    @Test
    public void notReadyAfterClose() {
        assertThat(call.isReady()).isTrue();
        call.close(Status.OK, new Metadata());
        await().untilAsserted(() -> assertThat(call.isReady()).isFalse());
    }

    @Test
    public void closedIfCancelled() {
        assertThat(call.isCancelled()).isFalse();
        completionFuture.completeExceptionally(ClosedSessionException.get());
        await().untilAsserted(() -> assertThat(call.isCancelled()).isTrue());
    }
}
