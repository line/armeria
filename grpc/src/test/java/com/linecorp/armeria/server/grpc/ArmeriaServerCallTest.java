/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.reactivestreams.Subscription;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcTestUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.DefaultEventExecutor;

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
                            DecompressorRegistry.getDefaultInstance().getAdvertisedMessageEncodings());

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
        call = new ArmeriaServerCall<>(
                HttpHeaders.of(),
                TestServiceGrpc.METHOD_UNARY_CALL,
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                UnpooledByteBufAllocator.DEFAULT,
                res,
                MAX_MESSAGE_BYTES,
                MAX_MESSAGE_BYTES,
                ctx);
        call.setListener(listener);
        call.messageReader().onSubscribe(subscription);
    }

    @Test
    public void nonStreamProtoMessage() throws Exception {
        ByteBuf request = GrpcTestUtil.requestByteBuf();
        assertThat(request.refCnt()).isEqualTo(1);
        call.messageRead(new ByteBufOrStream(request));
        verify(listener).onMessage(GrpcTestUtil.REQUEST_MESSAGE);
        assertThat(request.refCnt()).isEqualTo(0);
    }

    @Test
    public void streamProtoMessage() throws Exception {
        ByteBuf uncompressed = GrpcTestUtil.requestByteBuf();

        ByteBuf request = UnpooledByteBufAllocator.DEFAULT.buffer();

        // While any InputStream would be fine, let's go ahead and make sure a closed gzip
        // stream releases the buffer.
        try (ByteBufInputStream is = new ByteBufInputStream(uncompressed, true);
             GZIPOutputStream os = new GZIPOutputStream(new ByteBufOutputStream(request))) {
            ByteStreams.copy(is, os);
        }

        assertThat(uncompressed.refCnt()).isEqualTo(0);
        assertThat(request.refCnt()).isEqualTo(1);

        call.messageRead(new ByteBufOrStream(
                new GZIPInputStream(new ByteBufInputStream(request, true))));
        verify(listener).onMessage(GrpcTestUtil.REQUEST_MESSAGE);
        assertThat(request.refCnt()).isEqualTo(0);
    }

    @Test
    public void messageReadAfterClose_byteBuf() throws Exception {
        call.close(Status.ABORTED);

        call.messageRead(new ByteBufOrStream(GrpcTestUtil.requestByteBuf()));

        verify(listener, never()).onMessage(any());
    }

    @Test
    public void messageReadAfterClose_stream() throws Exception {
        call.close(Status.ABORTED);

        call.messageRead(new ByteBufOrStream(new ByteBufInputStream(GrpcTestUtil.requestByteBuf(), true)));

        verify(listener, never()).onMessage(any());
    }

    @Test
    public void readyOnStart() {
        assertThat(call.isReady()).isTrue();
    }

    @Test
    public void notReadyAfterClose() {
        assertThat(call.isReady()).isTrue();
        call.close(Status.OK);
        assertThat(call.isReady()).isFalse();
    }

    @Test
    public void headers_defaults() throws Exception {
        call.sendHeaders(new Metadata());
        HttpHeaders expectedHeaders = DEFAULT_RESPONSE_HEADERS;
        verify(res).write(expectedHeaders);
        verifyNoMoreInteractions(res);
    }

    @Test
    public void headers_noDecompressors() throws Exception {
        call = new ArmeriaServerCall<>(
                HttpHeaders.of(),
                TestServiceGrpc.METHOD_UNARY_CALL,
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.emptyInstance(),
                UnpooledByteBufAllocator.DEFAULT,
                res,
                MAX_MESSAGE_BYTES,
                MAX_MESSAGE_BYTES,
                ctx);
        call.sendHeaders(new Metadata());
        HttpHeaders expectedHeaders =
                HttpHeaders.of(HttpStatus.OK)
                           .set(AsciiString.of("content-type"), "application/grpc+proto")
                           .set(AsciiString.of("grpc-encoding"), "identity");
        verify(res).write(expectedHeaders);
        verifyNoMoreInteractions(res);
    }

    @Test
    public void headers_callCompressionClientNoAccepts() throws Exception {
        call.setCompression("gzip");
        call.setMessageCompression(true);
        call.sendHeaders(new Metadata());
        HttpHeaders expectedHeaders =
                HttpHeaders.of(HttpStatus.OK)
                           .set(AsciiString.of("content-type"), "application/grpc+proto")
                           .set(AsciiString.of("grpc-encoding"), "identity")
                           .set(AsciiString.of("grpc-accept-encoding"),
                                DecompressorRegistry.getDefaultInstance().getAdvertisedMessageEncodings());
        verify(res).write(expectedHeaders);
        verifyNoMoreInteractions(res);
    }

    @Test
    public void headers_callCompressionClientNoMatch() throws Exception {
        call = new ArmeriaServerCall<>(
                HttpHeaders.of().set(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, "pied-piper,quantum"),
                TestServiceGrpc.METHOD_UNARY_CALL,
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                UnpooledByteBufAllocator.DEFAULT,
                res,
                MAX_MESSAGE_BYTES,
                MAX_MESSAGE_BYTES,
                ctx);
        call.setCompression("gzip");
        call.setMessageCompression(true);
        call.sendHeaders(new Metadata());
        HttpHeaders expectedHeaders =
                HttpHeaders.of(HttpStatus.OK)
                           .set(AsciiString.of("content-type"), "application/grpc+proto")
                           .set(AsciiString.of("grpc-encoding"), "identity")
                           .set(AsciiString.of("grpc-accept-encoding"),
                                DecompressorRegistry.getDefaultInstance().getAdvertisedMessageEncodings());
        verify(res).write(expectedHeaders);
        verifyNoMoreInteractions(res);
    }

    @Test
    public void headers_callCompressionClientMatch() throws Exception {
        call = responseCompressionCall();
        call.setCompression("gzip");
        call.setMessageCompression(true);
        call.sendHeaders(new Metadata());
        HttpHeaders expectedHeaders =
                HttpHeaders.of(HttpStatus.OK)
                           .set(AsciiString.of("content-type"), "application/grpc+proto")
                           .set(AsciiString.of("grpc-encoding"), "gzip")
                           .set(AsciiString.of("grpc-accept-encoding"),
                                DecompressorRegistry.getDefaultInstance().getAdvertisedMessageEncodings());
        verify(res).write(expectedHeaders);
        verifyNoMoreInteractions(res);
    }

    @Test
    public void sendMessage() throws Exception {
        call.sendHeaders(new Metadata());
        call.sendMessage(GrpcTestUtil.RESPONSE_MESSAGE);

        verify(res).write(isA(HttpHeaders.class));
        verify(res).write(HttpData.of(GrpcTestUtil.uncompressedResponseBytes()));
        verifyNoMoreInteractions(res);

        verify(listener, times(1)).onReady();
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void sendCompressedMessage() throws Exception {
        call = responseCompressionCall();
        call.setListener(listener);
        call.setCompression("gzip");
        call.setMessageCompression(true);
        call.sendHeaders(new Metadata());
        call.sendMessage(GrpcTestUtil.RESPONSE_MESSAGE);

        verify(res).write(isA(HttpHeaders.class));
        verify(res).write(HttpData.of(GrpcTestUtil.compressedResponseBytes()));
        verifyNoMoreInteractions(res);

        verify(listener, times(1)).onReady();
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void error_noMessage() throws Exception {
        call.onError(Status.ABORTED);
        HttpHeaders expectedHeaders =
                HttpHeaders.of(HttpStatus.OK)
                           .set(AsciiString.of("content-type"), "application/grpc+proto")
                           .set(AsciiString.of("grpc-status"), "10");
        verify(res).write(expectedHeaders);
        verify(res).close();
        verifyNoMoreInteractions(res);
        verify(listener).onCancel();
        verify(subscription).cancel();
    }

    @Test
    public void error_withMessage() throws Exception {
        call.onError(Status.ABORTED.withDescription("aborted call"));
        HttpHeaders expectedHeaders =
                HttpHeaders.of(HttpStatus.OK)
                           .set(AsciiString.of("content-type"), "application/grpc+proto")
                           .set(AsciiString.of("grpc-status"), "10")
                           .set(AsciiString.of("grpc-message"), "aborted call");
        verify(res).write(expectedHeaders);
        verify(res).close();
        verifyNoMoreInteractions(res);
        verify(listener).onCancel();
        verify(subscription).cancel();
    }

    @Test
    public void fullCall_success() throws Exception {
        when(ctx.eventLoop()).thenReturn(new DefaultEventLoop(new DefaultEventExecutor()));
        call.request(2);
        ctx.eventLoop().shutdownGracefully().get();
        call.messageReader().onNext(HttpData.of(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())));
        verify(listener).onMessage(GrpcTestUtil.REQUEST_MESSAGE);
        call.endOfStream();
        verify(listener).onHalfClose();
        call.sendHeaders(new Metadata());
        call.sendMessage(GrpcTestUtil.RESPONSE_MESSAGE);
        verify(listener).onReady();
        call.close(Status.OK);
        verify(listener).onComplete();
        verifyNoMoreInteractions(listener);

        verify(res).write(DEFAULT_RESPONSE_HEADERS);
        verify(res).write(HttpData.of(GrpcTestUtil.uncompressedResponseBytes()));
        verify(res).write(HttpHeaders.of().set(AsciiString.of("grpc-status"), "0"));
        verify(res).close();
        verifyNoMoreInteractions(res);
    }

    @Test
    public void fullCall_error() throws Exception {
        when(ctx.eventLoop()).thenReturn(new DefaultEventLoop(new DefaultEventExecutor()));
        call.request(2);
        ctx.eventLoop().shutdownGracefully().get();
        call.messageReader().onNext(HttpData.of(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())));
        verify(listener).onMessage(GrpcTestUtil.REQUEST_MESSAGE);
        call.endOfStream();
        verify(listener).onHalfClose();
        call.sendHeaders(new Metadata());
        call.sendMessage(GrpcTestUtil.RESPONSE_MESSAGE);
        verify(listener).onReady();
        call.close(Status.ABORTED);
        verify(listener).onCancel();
        verifyNoMoreInteractions(listener);

        verify(res).write(DEFAULT_RESPONSE_HEADERS);
        verify(res).write(HttpData.of(GrpcTestUtil.uncompressedResponseBytes()));
        verify(res).write(HttpHeaders.of().set(AsciiString.of("grpc-status"), "10"));
        verify(res).close();
        verifyNoMoreInteractions(res);

        // Error happened after client stream ended.
        verify(subscription, never()).cancel();
    }

    @Test
    public void tooLargeRequest() throws Exception {
        when(ctx.eventLoop()).thenReturn(new DefaultEventLoop(new DefaultEventExecutor()));
        SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                        Strings.repeat("a", 1024))))
                             .build();
        call.request(2);
        ctx.eventLoop().shutdownGracefully().get();
        call.messageReader().onNext(
                HttpData.of(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.protoByteBuf(request))));
        verify(res).write(HttpHeaders.of(HttpStatus.OK)
                                     .set(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto")
                                     .set(AsciiString.of("grpc-status"), "13")
                                     .set(AsciiString.of("grpc-message"),
                                          "com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer: " +
                                          "Frame size 1030 exceeds maximum: 1024. "));
        verify(res).close();
        verifyNoMoreInteractions(res);
        verify(listener).onCancel();
        verifyNoMoreInteractions(listener);

        assertThat(call.messageReader().deframer.isClosed()).isTrue();
        verify(subscription).cancel();
    }

    @Test
    public void tooLargeRequestCompressed() throws Exception {
        when(ctx.eventLoop()).thenReturn(new DefaultEventLoop(new DefaultEventExecutor()));
        call = new ArmeriaServerCall<>(
                HttpHeaders.of().set(GrpcHeaderNames.GRPC_ENCODING, "gzip"),
                TestServiceGrpc.METHOD_UNARY_CALL,
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                UnpooledByteBufAllocator.DEFAULT, res,
                MAX_MESSAGE_BYTES,
                MAX_MESSAGE_BYTES,
                ctx);
        call.messageReader().onSubscribe(subscription);
        call.setListener(listener);
        call.setCompression("gzip");
        call.setMessageCompression(true);
        SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                        Strings.repeat("a", 1024))))
                             .build();
        call.request(2);
        ctx.eventLoop().shutdownGracefully().get();
        call.messageReader().onNext(
                HttpData.of(GrpcTestUtil.compressedFrame(GrpcTestUtil.protoByteBuf(request))));
        verify(res).write(
                HttpHeaders.of(HttpStatus.OK)
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto")
                           .set(AsciiString.of("grpc-status"), "13")
                           .set(AsciiString.of("grpc-message"),
                                "com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer: " +
                                "Compressed frame exceeds maximum frame size: 1024. Bytes read: 1030. "));
        verify(res).close();
        verifyNoMoreInteractions(res);
        verify(listener).onCancel();
        verifyNoMoreInteractions(listener);

        assertThat(call.messageReader().deframer.isClosed()).isTrue();
        verify(subscription).cancel();
    }

    private ArmeriaServerCall<SimpleRequest, SimpleResponse> responseCompressionCall() {
        return new ArmeriaServerCall<>(
                HttpHeaders.of().set(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, "pied-piper, gzip"),
                TestServiceGrpc.METHOD_UNARY_CALL,
                CompressorRegistry.getDefaultInstance(),
                DecompressorRegistry.getDefaultInstance(),
                UnpooledByteBufAllocator.DEFAULT,
                res,
                MAX_MESSAGE_BYTES,
                MAX_MESSAGE_BYTES,
                ctx);
    }

}
