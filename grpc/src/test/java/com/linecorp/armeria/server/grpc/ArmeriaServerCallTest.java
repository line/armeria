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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.logging.DefaultRequestLog;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;
import com.linecorp.armeria.internal.grpc.GrpcTestUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.util.AsciiString;

// TODO(anuraag): Currently only grpc-protobuf has been published so we only test proto here.
// Once grpc-thrift is published, add tests for thrift stubs which will not go through the
// optimized protobuf marshalling paths.
public class ArmeriaServerCallTest {

    private static final int MAX_MESSAGE_BYTES = 1024;

    private static final HttpHeaders DEFAULT_RESPONSE_HEADERS =
            HttpHeaders.of(HttpStatus.OK)
                       .set(AsciiString.of("content-type"), "application/grpc+proto")
                       .set(AsciiString.of("grpc-encoding"), "identity")
                       .set(AsciiString.of("grpc-accept-encoding"),
                            DecompressorRegistry.getDefaultInstance().getAdvertisedMessageEncodings())
                       .asImmutable();

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private HttpResponseWriter res;

    @Mock
    private ServerCall.Listener<SimpleRequest> listener;

    @Mock
    private Subscription subscription;

    @Mock
    private ServiceRequestContext ctx;

    private ArmeriaServerCall<SimpleRequest, SimpleResponse> call;

    @Before
    public void setUp() {
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
        call = new ArmeriaServerCall<>(
                HttpHeaders.of(),
                TestServiceGrpc.METHOD_UNARY_CALL,
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                res,
                MAX_MESSAGE_BYTES,
                MAX_MESSAGE_BYTES,
                ctx,
                GrpcSerializationFormats.PROTO,
                MessageMarshaller.builder().build());
        call.setListener(listener);
        call.messageReader().onSubscribe(subscription);
        when(ctx.logBuilder()).thenReturn(new DefaultRequestLog(ctx));
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
    }

    @Test
    public void messageReadAfterClose_byteBuf() throws Exception {
        call.close(Status.ABORTED, new Metadata());

        call.messageRead(new ByteBufOrStream(GrpcTestUtil.requestByteBuf()));

        verify(listener, never()).onMessage(any());
    }

    @Test
    public void messageReadAfterClose_stream() throws Exception {
        call.close(Status.ABORTED, new Metadata());

        call.messageRead(new ByteBufOrStream(new ByteBufInputStream(GrpcTestUtil.requestByteBuf(), true)));

        verify(listener, never()).onMessage(any());
    }

    @Test
    public void readyOnStart() {
        assertThat(call.isReady()).isTrue();
        call.messageReader().cancel();
    }

    @Test
    public void notReadyAfterClose() {
        assertThat(call.isReady()).isTrue();
        call.close(Status.OK, new Metadata());
        assertThat(call.isReady()).isFalse();
    }
}
