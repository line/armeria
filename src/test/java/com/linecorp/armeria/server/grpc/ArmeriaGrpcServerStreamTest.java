/*
 * Copyright 2016 LINE Corporation
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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.reactivestreams.Subscription;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ReadableBuffers;
import io.grpc.internal.ServerStreamListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

// Based on NettyServerStreamTest from grpc. The main difference is flow control tests aren't copied as they
// are handled within armeria itself.
public class ArmeriaGrpcServerStreamTest {

    private static final long MAX_MESSAGE_SIZE = 1 * 1024 * 1024;
    private static final String MESSAGE = "hello world";

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private HttpResponseWriter responseWriter;

    @Mock
    private ServerStreamListener serverListener;

    @Mock
    private Subscription subscription;

    private ArmeriaGrpcServerStream stream;

    @Before
    public void setUp() {
        stream = new ArmeriaGrpcServerStream(responseWriter, MAX_MESSAGE_SIZE);
        stream.transportState().setListener(serverListener);
        stream.messageReader().onSubscribe(subscription);
        verify(subscription).request(1);
        verify(serverListener, atLeastOnce()).onReady();
        verifyNoMoreInteractions(serverListener);
    }

    @Test
    public void inboundMessageShouldCallListener() throws Exception {
        stream.request(1);

        stream.transportState().inboundDataReceived(ReadableBuffers.wrap(messageFrame(MESSAGE)), false);
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(serverListener).messageRead(captor.capture());

        assertThat(MESSAGE).isEqualTo(toString(captor.getValue()));
    }

    @Test
    public void shouldBeImmediatelyReadyForData() {
        assertThat(stream.isReady()).isTrue();
    }

    @Test
    public void closedShouldNotBeReady() throws IOException {
        assertThat(stream.isReady()).isTrue();
        stream.close(Status.ABORTED, new Metadata());
        assertThat(stream.isReady()).isFalse();
    }

    @Test
    public void notifiedOnReadyAfterWriteCompletes() throws IOException {
        stream.writeHeaders(new Metadata());
        assertThat(stream.isReady()).isTrue();
        byte[] msg = largeMessage();
        // The future is set up to automatically complete, indicating that the write is done.
        stream.writeMessage(new ByteArrayInputStream(msg));
        stream.flush();
        assertThat(stream.isReady()).isTrue();
        verify(serverListener).onReady();
    }

    @Test
    public void writeHeadersAndResponse() throws Exception {
        stream.writeHeaders(new Metadata());
        byte[] msg = smallMessage();
        stream.writeMessage(new ByteArrayInputStream(msg));
        stream.flush();

        verify(responseWriter).write(HttpHeaders.of(HttpStatus.OK)
                                                .set(HttpHeaderNames.CONTENT_TYPE,
                                                     GrpcUtil.CONTENT_TYPE_GRPC));
        verify(responseWriter).write(HttpData.of(messageFrame(MESSAGE)));
        verifyNoMoreInteractions(responseWriter);
    }

    @Test
    public void closeBeforeClientHalfCloseShouldSucceed() throws Exception {
        stream.close(Status.OK, new Metadata());

        verify(responseWriter).write(new DefaultHttpHeaders(true, 0, true)
                                             .status(HttpStatus.OK)
                                             .set(HttpHeaderNames.CONTENT_TYPE,
                                                  GrpcUtil.CONTENT_TYPE_GRPC)
                                             .set(AsciiString.of("grpc-status"), "0"));
        verify(responseWriter).close();
        verifyNoMoreInteractions(responseWriter);
        verifyZeroInteractions(serverListener);

        stream.transportState().complete();
        verify(serverListener).closed(Status.OK);
        verifyNoMoreInteractions(serverListener);
    }

    @Test
    public void closeWithErrorBeforeClientHalfCloseShouldSucceed() throws Exception {
        // Error is sent on wire and ends the stream
        stream.close(Status.CANCELLED, new Metadata());

        verify(responseWriter).write(new DefaultHttpHeaders(true, 0, true)
                                             .status(HttpStatus.OK)
                                             .set(HttpHeaderNames.CONTENT_TYPE,
                                                  GrpcUtil.CONTENT_TYPE_GRPC)
                                             .set(AsciiString.of("grpc-status"), "1"));
        verify(responseWriter).close();
        verifyNoMoreInteractions(responseWriter);
        verifyZeroInteractions(serverListener);

        // Sending complete. Listener gets closed()
        stream.transportState().complete();
        verify(serverListener).closed(Status.OK);
        verifyNoMoreInteractions(serverListener);
    }

    @Test
    public void closeAfterClientHalfCloseShouldSucceed() throws Exception {
        // Client half-closes. Listener gets halfClosed()
        stream.transportState()
                .inboundDataReceived(ReadableBuffers.empty(), true);

        verify(serverListener).halfClosed();

        // Server closes. Status sent
        stream.close(Status.OK, new Metadata());
        verifyZeroInteractions(serverListener);

        verify(responseWriter).write(new DefaultHttpHeaders(true, 0, true)
                                             .status(HttpStatus.OK)
                                             .set(HttpHeaderNames.CONTENT_TYPE,
                                                  GrpcUtil.CONTENT_TYPE_GRPC)
                                             .set(AsciiString.of("grpc-status"), "0"));
        verify(responseWriter).close();
        verifyNoMoreInteractions(responseWriter);

        // Sending and receiving complete. Listener gets closed()
        stream.transportState().complete();
        verify(serverListener).closed(Status.OK);
        verifyNoMoreInteractions(serverListener);
    }

    @Test
    public void abortStreamAndNotSendStatus() throws Exception {
        Status status = Status.INTERNAL.withCause(new Throwable());
        stream.transportState().transportReportStatus(status);
        verify(serverListener).closed(same(status));
        verifyZeroInteractions(responseWriter);
        verifyNoMoreInteractions(serverListener);
    }

    @Test
    public void abortStreamAfterClientHalfCloseShouldCallClose() {
        Status status = Status.INTERNAL.withCause(new Throwable());
        // Client half-closes. Listener gets halfClosed()
        stream.transportState().inboundDataReceived(ReadableBuffers.empty(), true);
        verify(serverListener).halfClosed();
        // Abort from the transport layer
        stream.transportState().transportReportStatus(status);
        verify(serverListener).closed(same(status));
        verifyNoMoreInteractions(serverListener);
    }

    @Test
    public void cancelStreamShouldSucceed() {
        stream.cancel(Status.DEADLINE_EXCEEDED);
        verify(responseWriter).close(Status.DEADLINE_EXCEEDED.getCause());
        verifyNoMoreInteractions(responseWriter);
        verify(subscription).cancel();
    }

    private static byte[] smallMessage() {
        return MESSAGE.getBytes();
    }

    private static byte[] largeMessage() {
        byte[] smallMessage = smallMessage();
        int size = smallMessage.length * 10 * 1024;
        byte[] largeMessage = new byte[size];
        for (int ix = 0; ix < size; ix += smallMessage.length) {
            System.arraycopy(smallMessage, 0, largeMessage, ix, smallMessage.length);
        }
        return largeMessage;
    }

    private static byte[] messageFrame(String message) throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(os);
        dos.write(message.getBytes(StandardCharsets.UTF_8));
        dos.close();

        // Write the compression header followed by the context frame.
        return compressionFrame(os.toByteArray());
    }

    private static byte[] compressionFrame(byte[] data) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(0);
        buf.writeInt(data.length);
        buf.writeBytes(data);
        return ByteBufUtil.getBytes(buf);
    }

    private static String toString(InputStream in) throws Exception {
        byte[] bytes = ByteStreams.toByteArray(in);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
