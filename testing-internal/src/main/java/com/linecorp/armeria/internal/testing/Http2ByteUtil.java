/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.testing;

import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.client.ClientFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameTypes;

public final class Http2ByteUtil {

    public static ClientFactory newClientFactory(EventLoop eventLoop) {
        return ClientFactory.builder()
                            .useHttp2Preface(true)
                            // Set the window size to the HTTP/2 default values to simplify the traffic.
                            .http2InitialConnectionWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                            .http2InitialStreamWindowSize(Http2CodecUtil.DEFAULT_WINDOW_SIZE)
                            .workerGroup(eventLoop, false)
                            .build();
    }

    public static void handleInitialExchange(InputStream in, BufferedOutputStream out) throws IOException {
        // Read the connection preface and discard it.
        readBytes(in, connectionPrefaceBuf().readableBytes());

        // Read a SETTINGS frame.
        assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.SETTINGS);

        // Send a SETTINGS frame and the ack for the received SETTINGS frame.
        sendEmptySettingsAndAckFrame(out);

        // Read a SETTINGS ack frame.
        assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.SETTINGS);
    }

    public static byte[] readBytes(InputStream in, int length) throws IOException {
        final byte[] buf = new byte[length];
        ByteStreams.readFully(in, buf);
        return buf;
    }

    public static void sendEmptySettingsAndAckFrame(BufferedOutputStream bos) throws IOException {
        // Send an empty SETTINGS frame.
        bos.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00 });
        // Send a SETTINGS_ACK frame.
        bos.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00 });
        bos.flush();
    }

    public static int payloadLength(byte[] buf) {
        return (buf[0] & 0xff) << 16 | (buf[1] & 0xff) << 8 | (buf[2] & 0xff);
    }

    public static ByteBuf readFrame(InputStream in) throws IOException {
        final byte[] frameBuf = readBytes(in, 9);
        final int payloadLength = payloadLength(frameBuf);
        final ByteBuf buffer = Unpooled.buffer(9 + payloadLength);
        buffer.writeBytes(frameBuf);
        buffer.writeBytes(in, payloadLength);
        return buffer;
    }

    private Http2ByteUtil() {}
}
