/*
 * Copyright 2018 LINE Corporation
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

import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameTypes;

@Timeout(10)
class Http2GoAwayTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    /**
     * Server sends a GOAWAY frame after finishing all streams.
     */
    @Test
    void streamEndsBeforeGoAway() throws Exception {
        try (ServerSocket ss = new ServerSocket(0);
             ClientFactory clientFactory = newClientFactory()) {

            final int port = ss.getLocalPort();

            final WebClient client = WebClient.builder("h2c://127.0.0.1:" + port)
                                              .factory(clientFactory)
                                              .build();
            final CompletableFuture<AggregatedHttpResponse> future = client.get("/").aggregate();

            try (Socket s = ss.accept()) {

                final InputStream in = s.getInputStream();
                final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());
                handleInitialExchange(in, bos);

                // Read a HEADERS frame.
                assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.HEADERS);

                // Send a HEADERS frame to finish the response followed by a GOAWAY frame.
                bos.write(new byte[] {
                        0x00, 0x00, 0x06, 0x01, 0x25, 0x00, 0x00, 0x00, 0x03,
                        0x00, 0x00, 0x00, 0x00, 0x0f, (byte) 0x88
                });
                bos.write(new byte[] {
                        0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x03, // lastStreamId = 3
                        0x00, 0x00, 0x00, 0x00  // errorCode = 0
                });
                bos.flush();

                // Read a GOAWAY frame.
                assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.GO_AWAY);

                // Request should not fail.
                future.join();

                // The connection should be closed by the client because there is no pending request.
                assertThat(in.read()).isEqualTo(-1);
            }
        }
    }

    /**
     * Server sends GOAWAY before finishing all streams.
     */
    @Test
    void streamEndsAfterGoAway() throws Exception {
        try (ServerSocket ss = new ServerSocket(0);
             ClientFactory clientFactory = newClientFactory()) {

            final int port = ss.getLocalPort();

            final WebClient client = WebClient.builder("h2c://127.0.0.1:" + port)
                                              .factory(clientFactory)
                                              .build();
            final CompletableFuture<AggregatedHttpResponse> future = client.get("/").aggregate();

            try (Socket s = ss.accept()) {

                final InputStream in = s.getInputStream();
                final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());
                handleInitialExchange(in, bos);

                // Read a HEADERS frame.
                assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.HEADERS);

                // Send a GOAWAY frame first followed by a HEADERS frame.
                bos.write(new byte[] {
                        0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x03, // lastStreamId = 3
                        0x00, 0x00, 0x00, 0x00  // errorCode = 0
                });
                bos.write(new byte[] {
                        0x00, 0x00, 0x06, 0x01, 0x25, 0x00, 0x00, 0x00, 0x03,
                        0x00, 0x00, 0x00, 0x00, 0x0f, (byte) 0x88
                });
                bos.flush();

                // Read a GOAWAY frame.
                assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.GO_AWAY);

                // Request should not fail.
                future.join();

                // The connection should be closed by the client because there is no pending request.
                assertThat(in.read()).isEqualTo(-1);
            }
        }
    }

    /**
     * Client sends two requests whose streamIds are 3 and 5 respectively. Server sends a GOAWAY frame
     * whose lastStreamId is 3. The request with streamId 5 should fail.
     */
    @Test
    void streamGreaterThanLastStreamId() throws Exception {
        try (ServerSocket ss = new ServerSocket(0);
             ClientFactory clientFactory = newClientFactory()) {

            final int port = ss.getLocalPort();

            final WebClient client = WebClient.builder("h2c://127.0.0.1:" + port)
                                              .factory(clientFactory)
                                              .build();
            final CompletableFuture<AggregatedHttpResponse> future1 = client.get("/").aggregate();
            try (Socket s = ss.accept()) {

                final InputStream in = s.getInputStream();
                final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());
                handleInitialExchange(in, bos);

                // Read a HEADERS frame.
                assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.HEADERS);

                // Send the second request.
                final CompletableFuture<AggregatedHttpResponse> future2 = client.get("/").aggregate();

                // Read a HEADERS frame for the second request.
                assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.HEADERS);

                // Send a GOAWAY frame.
                bos.write(new byte[] {
                        0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x03, // lastStreamId = 3
                        0x00, 0x00, 0x00, 0x00  // errorCode = 0
                });
                bos.flush();

                // The second request should fail with UnprocessedRequestException
                // which has a cause of GoAwayReceivedException.
                assertThatThrownBy(future2::join).isInstanceOf(CompletionException.class)
                                                 .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                 .hasRootCauseInstanceOf(GoAwayReceivedException.class);

                // The first request should not fail.
                assertThat(future1).isNotDone();

                // Read a GOAWAY frame.
                assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.GO_AWAY);

                // Send a HEADERS frame for the first request.
                bos.write(new byte[] {
                        0x00, 0x00, 0x06, 0x01, 0x25, 0x00, 0x00, 0x00, 0x03,
                        0x00, 0x00, 0x00, 0x00, 0x0f, (byte) 0x88
                });
                bos.flush();

                // Request should not fail.
                future1.join();

                // The connection should be closed by the client because there is no pending request.
                assertThat(in.read()).isEqualTo(-1);
            }
        }
    }

    private static ClientFactory newClientFactory() {
        return ClientFactory.builder()
                            .useHttp2Preface(true)
                            // Set the window size to the HTTP/2 default values to simplify the traffic.
                            .http2InitialConnectionWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                            .http2InitialStreamWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                            .workerGroup(eventLoop.get(), false)
                            .build();
    }

    private static void handleInitialExchange(InputStream in, BufferedOutputStream out) throws IOException {
        // Read the connection preface and discard it.
        readBytes(in, connectionPrefaceBuf().readableBytes());

        // Read a SETTINGS frame.
        assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.SETTINGS);

        // Send a SETTINGS frame and the ack for the received SETTINGS frame.
        sendEmptySettingsAndAckFrame(out);

        // Read a SETTINGS ack frame.
        assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.SETTINGS);
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        final byte[] buf = new byte[length];
        ByteStreams.readFully(in, buf);
        return buf;
    }

    private static void sendEmptySettingsAndAckFrame(BufferedOutputStream bos) throws IOException {
        // Send an empty SETTINGS frame.
        bos.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00 });
        // Send a SETTINGS_ACK frame.
        bos.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00 });
        bos.flush();
    }

    private static int payloadLength(byte[] buf) {
        return (buf[0] & 0xff) << 16 | (buf[1] & 0xff) << 8 | (buf[2] & 0xff);
    }

    private static ByteBuf readFrame(InputStream in) throws IOException {
        final byte[] frameBuf = readBytes(in, 9);
        final int payloadLength = payloadLength(frameBuf);
        final ByteBuf buffer = Unpooled.buffer(9 + payloadLength);
        buffer.writeBytes(frameBuf);
        buffer.writeBytes(in, payloadLength);
        return buffer;
    }
}
