/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.apache.hc.core5.http2.hpack.HPackEncoder;
import org.apache.hc.core5.util.ByteArrayBuffer;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameTypes;

class ServerRequestPathEncodingTest {

    private static final String ABSOLUTE_PATH_PREFIX = "http://localhost:8080";
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(Route.builder().glob("/uri-valid/**").build(), (ctx, req) -> HttpResponse.of(204));
            sb.absoluteUriTransformer(uri -> {
                if (ABSOLUTE_PATH_PREFIX.equals(uri)) {
                    return "/uri-valid/[..foobar]";
                }
                return uri;
            });
        }
    };

    @ParameterizedTest
    @CsvSource({"/uri-valid/foobar/[..foobar],/uri-valid/foobar/%5B..foobar%5D",
                "/uri-valid/[..foobar]?q1=[]&q2=[..],/uri-valid/%5B..foobar%5D?q1=[]&q2=[..]",
                ABSOLUTE_PATH_PREFIX + ",/uri-valid/%5B..foobar%5D"})
    void requestUriEncodedHttp1(String path, String expected) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(server.httpSocketAddress());
            s.getOutputStream().write(
                    ("GET " + path + " HTTP/1.1\r\n" +
                     "Host:" + server.httpUri().getAuthority() + "\r\n" +
                     "Connection: close\r\n" +
                     "\r\n").getBytes(StandardCharsets.US_ASCII));
            final String ret = new String(ByteStreams.toByteArray(s.getInputStream()),
                                          StandardCharsets.US_ASCII);
            assertThat(ret).contains("HTTP/1.1 204 No Content");
        }
        final ServiceRequestContextCaptor captor = server.requestContextCaptor();
        assertThat(captor.size()).isEqualTo(1);
        final ServiceRequestContext ctx = captor.poll();
        assertThat(ctx.request().uri().toString()).isEqualTo(server.httpUri() + expected);
    }

    @ParameterizedTest
    @CsvSource({"/uri-valid/foobar/[..foobar],/uri-valid/foobar/%5B..foobar%5D",
                "/uri-valid/[..foobar]?q1=[]&q2=[..],/uri-valid/%5B..foobar%5D?q1=[]&q2=[..]"})
    void requestUriEncodedHttp2(String path, String expected) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(server.httpSocketAddress());

            // start a http2 connection with a preface
            s.getOutputStream().write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            ByteBuf buf = Unpooled.buffer(FRAME_HEADER_LENGTH);
            buf.writeMedium(0);
            buf.writeByte(Http2FrameTypes.SETTINGS);
            buf.writeByte(new Http2Flags().value());
            buf.writeInt(0);
            s.getOutputStream().write(buf.array());

            // acknowledge the server settings preemptively
            buf = Unpooled.buffer(FRAME_HEADER_LENGTH);
            buf.writeMedium(0);
            buf.writeByte(Http2FrameTypes.SETTINGS);
            buf.writeByte(new Http2Flags().ack(true).value());
            buf.writeInt(0);
            s.getOutputStream().write(buf.array());

            // write headers
            final byte[] headerBytes = headerBytes(path);
            buf = Unpooled.buffer(FRAME_HEADER_LENGTH + headerBytes.length);
            buf.writeMedium(headerBytes.length);
            buf.writeByte(Http2FrameTypes.HEADERS);
            buf.writeByte(new Http2Flags().endOfHeaders(true).endOfStream(true).value());
            buf.writeInt(3);
            buf.writeBytes(headerBytes);
            s.getOutputStream().write(buf.array());

            // goaway
            buf = Unpooled.buffer(8 + FRAME_HEADER_LENGTH);
            buf.writeMedium(8);
            buf.writeByte(Http2FrameTypes.GO_AWAY);
            buf.writeByte(new Http2Flags().value());
            buf.writeInt(0);
            buf.writeInt(0);
            s.getOutputStream().write(buf.array());

            // check that at least the user-agent was included as a response
            final byte[] result = ByteStreams.toByteArray(s.getInputStream());
            assertThat(result).contains("armeria".getBytes(StandardCharsets.UTF_8));

            final ServiceRequestContextCaptor captor = server.requestContextCaptor();
            await().until(() -> captor.size() > 0);
            assertThat(captor.size()).isEqualTo(1);
            final ServiceRequestContext ctx = captor.poll();

            assertThat(ctx.request().uri().toString()).isEqualTo(server.httpUri() + expected);
        }
    }

    private static byte[] headerBytes(String path) throws Exception {
        final HPackEncoder encoder = new HPackEncoder(StandardCharsets.UTF_8);
        final ByteArrayBuffer buffer = new ByteArrayBuffer(1024);
        encoder.encodeHeader(buffer, ":method", "GET", false);
        encoder.encodeHeader(buffer, ":authority", server.httpUri().getAuthority(), false);
        encoder.encodeHeader(buffer, ":path", path, false);
        return buffer.toByteArray();
    }
}
