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

package com.linecorp.armeria.client;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.AggregatedHttpMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2EventAdapter;
import io.netty.handler.codec.http2.Http2Exception;

public class Http2ClientSettingsTest {

    private static final EventLoop EVENT_LOOP = new DefaultEventLoop();

    private static final byte[] EMPTY_DATA = new byte[DEFAULT_MAX_FRAME_SIZE];

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Test
    public void initialConnectionAndStreamWindowSize() throws Exception {

        try (ServerSocket ss = new ServerSocket(0)) {
            final int port = ss.getLocalPort();

            final ClientFactory clientFactory = new ClientFactoryBuilder()
                    .useHttp2Preface(true)
                    // Client sends a WINDOW_UPDATE frame for connection when it receives 64 * 1024 bytes.
                    .initialHttp2ConnectionWindowSize(128 * 1024)
                    // Client sends a WINDOW_UPDATE frame for stream when it receives 48 * 1024 bytes.
                    .initialHttp2StreamWindowSize(96 * 1024)
                    .build();

            final HttpClient client = HttpClient.of(clientFactory, "h2c://localhost:" + port);
            final CompletableFuture<AggregatedHttpMessage> future = client.get("/").aggregate();

            try (Socket s = ss.accept()) {

                final InputStream in = s.getInputStream();
                final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());

                // Read the connection preface and discard it.
                readBytes(in, connectionPrefaceBuf().readableBytes());

                // Read a SETTINGS frame and validate it.
                assertSettingsFrameOfWindowSize(in);
                sendEmptySettingsAndAckFrame(bos);

                // Read a WINDOW_UPDATE frame and validate it.
                assertInitialWindowUpdateFrame(in);

                readBytes(in, 9); // Read a SETTINGS_ACK frame and discard it.
                readHeadersFrame(in); // Read a HEADERS frame and discard it.

                sendHeaderFrame(bos);

                ////////////////////////////////////////
                // Transmission of data gets started. //
                ////////////////////////////////////////

                send49151Bytes(bos); // 49151 == (96 * 1024 / 2 - 1) half of initial stream window size.

                int availableBytes = checkReadableForShortPeriod(in);
                assertThat(availableBytes).isZero(); // Nothing to read.

                // Send a DATA frame that indicates sending data 1 byte for stream id 03.
                bos.write(new byte[] { 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03 });
                bos.write(new byte[1]); // Triggers the client to send a WINDOW_UPDATE frame for stream id 03.
                bos.flush();

                // Read a WINDOW_UPDATE frame and validate it.
                assertWindowUpdateFrameFor03(in);

                // Send a DATA frame that indicates sending data as much as (0x4000 - 1) for stream id 03.
                bos.write(new byte[] { 0x00, 0x3f, (byte) 0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03 });
                bos.write(EMPTY_DATA, 0, 16383);

                availableBytes = checkReadableForShortPeriod(in);
                assertThat(availableBytes).isZero(); // Nothing to read.

                // Send a DATA frame that indicates sending data 1 byte for stream id 03.
                bos.write(new byte[] { 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03 });
                bos.write(new byte[1]); // Triggers the client to send a WINDOW_UPDATE frame for the connection.
                bos.flush();

                // Read an WINDOW_UPDATE frame and validate it.
                assertConnectionWindowUpdateFrame(in);

                // Send a DATA frame that indicates the end of stream id 03.
                bos.write(new byte[] { 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x03 });
                bos.flush();

                future.join();
            }
        }
    }

    @Test
    public void maxFrameSize() throws Exception {

        try (ServerSocket ss = new ServerSocket(0)) {
            final int port = ss.getLocalPort();

            final ClientFactory clientFactory = new ClientFactoryBuilder()
                    .useHttp2Preface(true)
                    .http2MaxFrameSize(DEFAULT_MAX_FRAME_SIZE * 2) // == 16384 * 2
                    .build();

            final HttpClient client = HttpClient.of(clientFactory, "http://127.0.0.1:" + port);
            final CompletableFuture<AggregatedHttpMessage> future = client.get("/").aggregate();

            try (Socket s = ss.accept()) {
                final InputStream in = s.getInputStream();
                final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());

                readBytes(in, connectionPrefaceBuf().capacity()); // Read the connection preface and discard it.

                // Read a SETTINGS frame and validate it.
                assertSettingsFrameOfMaxFrameSize(in);

                sendEmptySettingsAndAckFrame(bos);

                readBytes(in, 9); // Read a SETTINGS_ACK frame and discard it.
                readHeadersFrame(in); // Read a HEADERS frame and discard it.

                sendHeaderFrame(bos);

                ////////////////////////////////////////
                // Transmission of data gets started. //
                ////////////////////////////////////////

                // Send a DATA frame that indicates sending data as much as 0x8000 for stream id 03.
                bos.write(new byte[] { 0x00, (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03 });
                bos.write(EMPTY_DATA);
                bos.write(EMPTY_DATA);
                bos.flush();

                readBytes(in, 13); // Read a WINDOW_UPDATE frame for connection and discard it.
                readBytes(in, 13); // Read a WINDOW_UPDATE frame for stream id 03 and discard it.

                // Send a DATA frame that exceed MAX_FRAME_SIZE by 1.
                bos.write(new byte[] { 0x00, (byte) 0x80, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03 });
                bos.flush(); // Triggers the client to send a GOAWAY frame for the connection.

                // The client send a GOAWAY frame and the server read it.
                final ByteBuf buffer = readGoAwayFrame(in);
                final DefaultHttp2FrameReader frameReader = new DefaultHttp2FrameReader();

                final CountDownLatch latch = new CountDownLatch(1);
                frameReader.readFrame(null, buffer, new Http2EventAdapter() {
                    @Override
                    public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode,
                                             ByteBuf debugData)
                            throws Http2Exception {
                        assertThat(lastStreamId).isZero(); // 0: connection error
                        assertThat(errorCode).isEqualTo(Http2Error.FRAME_SIZE_ERROR.code());
                        latch.countDown();
                    }
                });
                latch.await();
                buffer.release();
            }
        }
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        final byte[] buf = new byte[length];
        ByteStreams.readFully(in, buf);
        return buf;
    }

    private static void assertSettingsFrameOfWindowSize(InputStream in) throws IOException {
        final byte[] settingsFrameWithInitialStreamWindowSize = readBytes(in, 15);

        final byte[] expected = {
                0x00, 0x00, 0x06, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x04, 0x00, 0x01, (byte) 0x80, 0x00
        };

        // client sent initial stream window size of 0x18000 which is 96 * 1024.
        assertThat(settingsFrameWithInitialStreamWindowSize).containsExactly(expected);
    }

    private static void sendEmptySettingsAndAckFrame(BufferedOutputStream bos) throws IOException {
        // Send an empty SETTINGS frame.
        bos.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00 });
        // Send a SETTINGS_ACK frame.
        bos.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00 });
        bos.flush();
    }

    private static void assertInitialWindowUpdateFrame(InputStream in) throws IOException {
        final byte[] initialWindowUpdateFrameForConnection = readBytes(in, 13);

        final byte[] expected = {
                0x00, 0x00, 0x04, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x01
        };

        // client sent the size of delta for initial connection window 0x10001 which is (128 * 1024) - 65535
        // where 65535 is default initial connection size.
        assertThat(initialWindowUpdateFrameForConnection).containsExactly(expected);
    }

    private static void readHeadersFrame(InputStream in) throws IOException {
        final byte[] headersFrameBuf = readBytes(in, 9);
        final int payloadLength = payloadLength(headersFrameBuf);
        readBytes(in, payloadLength);
    }

    private static int payloadLength(byte[] buf) {
        return (buf[0] & 0xff) << 16 | (buf[1] & 0xff) << 8 | (buf[2] & 0xff);
    }

    private static void sendHeaderFrame(BufferedOutputStream bos) throws IOException {
        // Send a HEADER frame with END_HEADERS and PRIORITY bits set for stream id 03.
        bos.write(new byte[] {
                0x00, 0x00, 0x06, 0x01, 0x24, 0x00, 0x00, 0x00, 0x03,
                0x00, 0x00, 0x00, 0x00, 0x0f, (byte) 0x88
        });
    }

    private static void send49151Bytes(BufferedOutputStream bos) throws IOException {
        // Send a DATA frame that indicates sending data as much as 0x4000 for stream id 03.
        bos.write(new byte[] { 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03 });
        bos.write(EMPTY_DATA);

        // Send a DATA frame that indicates sending data as much as 0x4000 for stream id 03.
        bos.write(new byte[] { 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03 });
        bos.write(EMPTY_DATA);

        // Send a DATA frame that indicates sending data as much as 0x4000 - 1 for stream id 03.
        bos.write(new byte[] { 0x00, 0x3f, (byte) 0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03 });
        bos.write(EMPTY_DATA, 0, 16383);
        bos.flush();
    }

    private static int checkReadableForShortPeriod(InputStream in) throws Exception {
        Future<Integer> future = EVENT_LOOP.schedule(in::available, 500, TimeUnit.MILLISECONDS);
        return future.get();
    }

    private static void assertWindowUpdateFrameFor03(InputStream in) throws IOException {
        final byte[] windowUpdateFrameFor03 = readBytes(in, 13);

        final byte[] expected = {
                0x00, 0x00, 0x04, 0x08, 0x00, 0x00, 0x00, 0x00, 0x03,
                0x00, 0x00, (byte) 0xc0, 0x00
        };

        // client sent a WINDOW_UPDATE frame for stream id 03 0xc000 which is (96 * 1024) / 2.
        assertThat(windowUpdateFrameFor03).containsExactly(expected);
    }

    private static void assertConnectionWindowUpdateFrame(InputStream in) throws IOException {
        final byte[] windowUpdateFrameForConnection = readBytes(in, 13);

        final byte[] expected = {
                0x00, 0x00, 0x04, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x00
        };

        // client sent a WINDOW_UPDATE frame for connection 0x10000 which is (128 * 1024) / 2.
        assertThat(windowUpdateFrameForConnection).containsExactly(expected);
    }

    private static void assertSettingsFrameOfMaxFrameSize(InputStream in) throws IOException {
        final byte[] settingsFrameWithMaxFrameSize = readBytes(in, 15);

        final byte[] expected = {
                0x00, 0x00, 0x06, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x05, 0x00, 0x00, (byte) 0x80, 0x00
        };

        // client sent a SETTINGS_MAX_FRAME_SIZE of 0x8000.
        assertThat(settingsFrameWithMaxFrameSize).containsExactly(expected);
    }

    private static ByteBuf readGoAwayFrame(InputStream in) throws IOException {
        // Read a GOAWAY frame.
        final byte[] goAwayFrameBuf = readBytes(in, 9);
        final int payloadLength = payloadLength(goAwayFrameBuf);
        final ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer(9 + payloadLength);
        buffer.writeBytes(goAwayFrameBuf);
        buffer.writeBytes(in, payloadLength);
        return buffer;
    }
}
