/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.server.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.internal.common.websocket.WebSocketFrameEncoder;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.HttpHeaderValues;

class WebSocketServiceTest {

    private static final WebSocketFrameEncoder encoder = WebSocketFrameEncoder.of(true);

    private HttpRequestWriter req;
    private ServiceRequestContext ctx;

    @BeforeEach
    void setUp() {
        req = HttpRequest.streaming(webSocketUpgradeHeaders());
        ctx = ServiceRequestContext.builder(req)
                                   .sessionProtocol(SessionProtocol.H1C)
                                   .build();
    }

    private static RequestHeaders webSocketUpgradeHeaders() {
        return RequestHeaders.builder(HttpMethod.GET, "/chat")
                             .add(HttpHeaderNames.CONNECTION,
                                  HttpHeaderValues.UPGRADE.toString() + ',' +
                                  // It works even if the header contains multiple values
                                  HttpHeaderValues.KEEP_ALIVE)
                             .add(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET +
                                                           ", additional_value")
                             .add(HttpHeaderNames.HOST, "foo.com")
                             .add(HttpHeaderNames.ORIGIN, "http://foo.com")
                             .addInt(HttpHeaderNames.SEC_WEBSOCKET_VERSION, 13)
                             .add(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
                             .add(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "superchat")
                             .build();
    }

    static void checkCloseFrame(HttpData httpData, WebSocketCloseStatus closeStatus) {
        // 0 ~ 3 FIN, RSV1, RSV2, RSV3. 4 ~ 7 opcode
        final ByteBuf byteBuf = httpData.byteBuf();
        assertThat(byteBuf.readByte() & 0x0F).isEqualTo(WebSocketFrameType.CLOSE.opcode());
        // Skip 1 bytes.
        byteBuf.readByte();
        assertThat((int) byteBuf.readShort()).isEqualTo(closeStatus.code());
    }

    @Test
    void testDecodedContinuationFrame() throws Exception {
        final CompletableFuture<List<WebSocketFrame>> collectFuture = new CompletableFuture<>();
        final WebSocketService webSocketService = WebSocketService.of((ctx, in) -> {
            in.collect().thenAccept(collectFuture::complete);
            return WebSocket.streaming();
        });

        final HttpResponse response = webSocketService.serve(ctx, req);
        req.write(toHttpData(WebSocketFrame.ofText("foo", false)));
        req.write(toHttpData(WebSocketFrame.ofContinuation("bar", true)));
        req.write(toHttpData(WebSocketFrame.ofBinary("foo".getBytes(StandardCharsets.UTF_8), false)));
        req.write(toHttpData(WebSocketFrame.ofContinuation("bar".getBytes(StandardCharsets.UTF_8), true)));
        req.close();

        response.subscribe(NoopSubscriber.get());
        final List<WebSocketFrame> frames = collectFuture.join();
        assertThat(frames.size()).isEqualTo(4);
        WebSocketFrame frame = frames.get(0);
        assertThat(frame.isFinalFragment()).isFalse();
        assertThat(frame.type()).isSameAs(WebSocketFrameType.TEXT);
        assertThat(frame.text()).isEqualTo("foo");

        frame = frames.get(1);
        assertThat(frame.isFinalFragment()).isTrue();
        assertThat(frame.type()).isSameAs(WebSocketFrameType.CONTINUATION);
        assertThat(frame.text()).isEqualTo("bar");

        frame = frames.get(2);
        assertThat(frame.isFinalFragment()).isFalse();
        assertThat(frame.type()).isSameAs(WebSocketFrameType.BINARY);
        assertThat(frame.text()).isEqualTo("foo");

        frame = frames.get(3);
        assertThat(frame.isFinalFragment()).isTrue();
        assertThat(frame.type()).isSameAs(WebSocketFrameType.CONTINUATION);
        assertThat(frame.text()).isEqualTo("bar");
    }

    private HttpData toHttpData(WebSocketFrame frame) {
        return HttpData.wrap(encoder.encode(ctx, frame));
    }

    static class AbstractWebSocketHandler implements WebSocketServiceHandler {

        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
            final WebSocketWriter writer = WebSocket.streaming();
            in.subscribe(new Subscriber<WebSocketFrame>() {
                @Override
                public void onSubscribe(Subscription s) {
                    onOpen(writer);
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketFrame webSocketFrame) {
                    try (WebSocketFrame frame = webSocketFrame) {
                        switch (frame.type()) {
                            case TEXT:
                                onText(writer, frame.text());
                                break;
                            case BINARY:
                                onBinary(writer, frame.byteBuf(ByteBufAccessMode.RETAINED_DUPLICATE));
                                break;
                            case CLOSE:
                                assertThat(frame).isInstanceOf(CloseWebSocketFrame.class);
                                final CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
                                onClose(writer, closeFrame.status(), closeFrame.reasonPhrase());
                                break;
                            default:
                                // no-op
                        }
                    } catch (Throwable t) {
                        writer.close(t);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    writer.close(t);
                }

                @Override
                public void onComplete() {
                    writer.close();
                }
            });
            return writer;
        }

        void onOpen(WebSocketWriter writer) {}

        void onText(WebSocketWriter writer, String message) {}

        void onBinary(WebSocketWriter writer, ByteBuf message) {
            try {
                if (message.hasArray()) {
                    onBinary(writer, message.array());
                } else {
                    onBinary(writer, ByteBufUtil.getBytes(message));
                }
            } finally {
                message.release();
            }
        }

        void onBinary(WebSocketWriter writer, byte[] message) {}

        void onClose(WebSocketWriter writer, WebSocketCloseStatus status, String reason) {
            writer.close(status, reason);
        }
    }
}
