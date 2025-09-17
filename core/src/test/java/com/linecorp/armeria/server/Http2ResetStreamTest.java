/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.internal.testing.netty.SimpleHttp2Connection;
import com.linecorp.armeria.internal.testing.netty.SimpleHttp2Connection.Http2Stream;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.logging.LogLevel;
import io.netty.util.ReferenceCountUtil;

class Http2ResetStreamTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of("hello"));
            sb.service("/ws", WebSocketService.builder((ctx, in) -> {
                final WebSocketWriter out = WebSocket.streaming();
                in.collect().whenComplete((unused, err) -> {
                    out.close();
                });
                return out;
            }).allowedOrigin(ignored -> true).build());
        }
    };

    @Test
    void rstStreamShouldNotBeRecv() throws Exception {
        final Deque<Integer> rstStreamFrames = new ArrayDeque<>();
        final Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, Http2ResetStreamTest.class) {
            @Override
            public void logRstStream(Direction direction, ChannelHandlerContext ctx, int streamId,
                                     long errorCode) {
                rstStreamFrames.offer(streamId);
                super.logRstStream(direction, ctx, streamId, errorCode);
            }
        };
        try (SimpleHttp2Connection conn = SimpleHttp2Connection.of(server.httpUri(), frameLogger);
             Http2Stream stream = conn.createStream()) {
            final DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method("GET");
            headers.path("/");
            final Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(headers, true);
            stream.sendFrame(headersFrame).syncUninterruptibly();

            await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
            final Http2Frame responseHeaderFrame = stream.take();
            assertThat(responseHeaderFrame).isInstanceOf(Http2HeadersFrame.class);
            assertThat(((Http2HeadersFrame) responseHeaderFrame).headers().status())
                    .asString().isEqualTo("200");

            Http2Frame dataFrame = stream.take();
            assertThat(dataFrame).isInstanceOf(Http2DataFrame.class);
            assertThat(((Http2DataFrame) dataFrame).content().toString(StandardCharsets.UTF_8))
                    .asString().isEqualTo("hello");
            ReferenceCountUtil.release(dataFrame);

            dataFrame = stream.take();
            assertThat(dataFrame).isInstanceOf(Http2DataFrame.class);
            assertThat(((Http2DataFrame) dataFrame).isEndStream()).isTrue();
            ReferenceCountUtil.release(dataFrame);

            Thread.sleep(1000);
            assertThat(rstStreamFrames).isEmpty();
        }
    }

    @Test
    void resetForWebsockets() throws Exception {
        final Deque<Integer> rstStreamFrames = new ArrayDeque<>();
        final Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, Http2ResetStreamTest.class) {
            @Override
            public void logRstStream(Direction direction, ChannelHandlerContext ctx, int streamId,
                                     long errorCode) {
                rstStreamFrames.offer(streamId);
                super.logRstStream(direction, ctx, streamId, errorCode);
            }
        };
        try (SimpleHttp2Connection conn = SimpleHttp2Connection.of(server.httpUri(), frameLogger);
             Http2Stream stream = conn.createStream()) {
            final DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method("CONNECT");
            headers.path("/ws");
            headers.set(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString());
            headers.set(HttpHeaderNames.ORIGIN, "localhost");
            headers.set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
            final Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(headers, false);
            stream.sendFrame(headersFrame).syncUninterruptibly();

            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final CloseWebSocketFrame closeFrame = WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE);
            final ByteBuf closeBuf = WebSocketFrameEncoder.of(true).encode(ctx, closeFrame);
            stream.sendFrame(new DefaultHttp2DataFrame(closeBuf)).syncUninterruptibly();
            stream.sendFrame(new DefaultHttp2DataFrame(true)).syncUninterruptibly();

            Http2Frame frame = stream.take();
            assertThat(frame).isInstanceOf(Http2HeadersFrame.class);
            assertThat(((Http2HeadersFrame) frame).headers().status()).asString().isEqualTo("200");
            ReferenceCountUtil.release(frame);

            frame = stream.take();
            assertThat(frame).isInstanceOf(Http2DataFrame.class);
            assertThat(((Http2DataFrame) frame).content().toString(StandardCharsets.UTF_8)).endsWith("Bye");
            ReferenceCountUtil.release(frame);

            frame = stream.take();
            assertThat(frame).isInstanceOf(Http2DataFrame.class);
            assertThat(((Http2DataFrame) frame).isEndStream()).isTrue();
            ReferenceCountUtil.release(frame);

            Thread.sleep(1000);
            assertThat(rstStreamFrames).isEmpty();
        }
    }
}
