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

package com.linecorp.armeria.internal.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import java.io.InputStream;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;

import io.grpc.Codec.Gzip;
import io.grpc.StatusRuntimeException;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

public class ArmeriaMessageDeframerTest {

    private static final int MAX_MESSAGE_SIZE = 1024;

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private ArmeriaMessageDeframer.Listener listener;

    private ArmeriaMessageDeframer deframer;

    @Before
    public void setUp() {
        deframer = new ArmeriaMessageDeframer(listener,
                                              MAX_MESSAGE_SIZE,
                                              UnpooledByteBufAllocator.DEFAULT)
                .decompressor(new Gzip());
    }

    @After
    public void tearDown() {
        deframer.close();
    }

    @Test
    public void noRequests() {
        // Deframer is considered stalled even when there are no pending deliveries. This allows the
        // HttpStreamReader to know when to request Http objects from the stream.
        assertThat(deframer.isStalled()).isTrue();
    }

    @Test
    public void request_noDataYet() {
        deframer.request(1);
        assertThat(deframer.isStalled()).isTrue();
    }

    @Test
    public void deframe_noRequests() throws Exception {
        deframer.deframe(HttpData.of(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())), false);
        assertThat(deframer.isStalled()).isFalse();
        verifyZeroInteractions(listener);

        deframer.request(1);
        verifyAndReleaseMessage(new ByteBufOrStream(GrpcTestUtil.requestByteBuf()));
        assertThat(deframer.isStalled()).isTrue();
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void deframe_hasRequests() throws Exception {
        deframer.request(1);
        deframer.deframe(HttpData.of(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())), false);
        verifyAndReleaseMessage(new ByteBufOrStream(GrpcTestUtil.requestByteBuf()));
        verifyNoMoreInteractions(listener);
        assertThat(deframer.isStalled()).isTrue();
    }

    @Test
    public void deframe_multipleFrames() throws Exception {
        byte[] frameBytes = GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf());
        deframer.request(1);
        deframer.deframe(HttpData.of(Arrays.copyOfRange(frameBytes, 0, 5)), false);
        verifyZeroInteractions(listener);
        assertThat(deframer.isStalled()).isTrue();
        deframer.deframe(HttpData.of(Arrays.copyOfRange(frameBytes, 5, frameBytes.length)), false);
        verifyAndReleaseMessage(new ByteBufOrStream(GrpcTestUtil.requestByteBuf()));
        verifyNoMoreInteractions(listener);
        assertThat(deframer.isStalled()).isTrue();
    }

    @Test
    public void deframe_multipleMessagesBeforeRequests() throws Exception {
        deframer.deframe(HttpData.of(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())), false);
        deframer.deframe(HttpData.of(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())), false);
        deframer.request(1);
        assertThat(deframer.isStalled()).isFalse();
        verifyAndReleaseMessage(new ByteBufOrStream(GrpcTestUtil.requestByteBuf()));
        verifyNoMoreInteractions(listener);
        reset(listener);
        deframer.request(1);
        assertThat(deframer.isStalled()).isTrue();
        verifyAndReleaseMessage(new ByteBufOrStream(GrpcTestUtil.requestByteBuf()));
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void deframe_multipleMessagesAfterRequests() throws Exception {
        deframer.request(2);
        deframer.deframe(HttpData.of(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())), false);
        deframer.deframe(HttpData.of(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())), false);
        assertThat(deframer.isStalled()).isTrue();
        verifyAndReleaseMessage(new ByteBufOrStream(GrpcTestUtil.requestByteBuf()), 2);
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void deframe_endOfStream() throws Exception {
        deframer.request(1);
        deframer.deframe(HttpData.EMPTY_DATA, true);
        verify(listener).endOfStream();
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void deframe_compressed() throws Exception {
        deframer.request(1);
        deframer.deframe(HttpData.of(GrpcTestUtil.compressedFrame(GrpcTestUtil.requestByteBuf())), false);
        ArgumentCaptor<ByteBufOrStream> messageCaptor = ArgumentCaptor.forClass(ByteBufOrStream.class);
        verify(listener).messageRead(messageCaptor.capture());
        verifyNoMoreInteractions(listener);
        ByteBufOrStream message = messageCaptor.getValue();
        assertThat(message.stream()).isNotNull();
        final byte[] messageBytes;
        try (InputStream stream = message.stream()) {
            messageBytes = ByteStreams.toByteArray(stream);
        }
        assertThat(messageBytes).isEqualTo(GrpcTestUtil.REQUEST_MESSAGE.toByteArray());
    }

    @Test
    public void deframe_tooLargeUncompressed() throws Exception {
        SimpleRequest request = SimpleRequest.newBuilder()
                                             .setPayload(Payload.newBuilder()
                                                                .setBody(ByteString.copyFromUtf8(
                                                                        Strings.repeat("a", 1024))))
                                             .build();
        byte[] frame = GrpcTestUtil.uncompressedFrame(Unpooled.wrappedBuffer(request.toByteArray()));
        assertThat(frame.length).isGreaterThan(1024);
        deframer.request(1);
        assertThatThrownBy(() -> deframer.deframe(HttpData.of(frame), false))
                .isInstanceOf(StatusRuntimeException.class);
    }

    @Test
    public void deframe_tooLargeCompressed() throws Exception {
        // Simple repeated character compresses below the frame threshold but uncompresses above it.
        SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(
                                                        Strings.repeat("a", 1024))))
                             .build();
        byte[] frame = GrpcTestUtil.compressedFrame(Unpooled.wrappedBuffer(request.toByteArray()));
        assertThat(frame.length).isLessThan(1024);
        deframer.request(1);
        deframer.deframe(HttpData.of(frame), false);
        ArgumentCaptor<ByteBufOrStream> messageCaptor = ArgumentCaptor.forClass(ByteBufOrStream.class);
        verify(listener).messageRead(messageCaptor.capture());
        verifyNoMoreInteractions(listener);
        try (InputStream stream = messageCaptor.getValue().stream()) {
            assertThatThrownBy(() -> ByteStreams.toByteArray(stream))
                    .isInstanceOf(StatusRuntimeException.class);
        }
    }

    private void verifyAndReleaseMessage(ByteBufOrStream message) {
        verifyAndReleaseMessage(message, 1);
    }

    private void verifyAndReleaseMessage(ByteBufOrStream message, int times) {
        ArgumentCaptor<ByteBufOrStream> read = ArgumentCaptor.forClass(ByteBufOrStream.class);
        verify(listener, times(times)).messageRead(read.capture());
        for (int i = 0; i < times; i++) {
            ByteBufOrStream val = read.getAllValues().get(i);
            assertThat(val).isEqualTo(message);
            if (val.buf() != null) {
                val.buf().release();
            }
        }
        if (message.buf() != null) {
            message.buf().release();
        }
    }
}
