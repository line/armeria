/*
 * Copyright 2021 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.TextWebSocketFrame;
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

    private static final AbstractWebSocketHandler handler = new AbstractWebSocketHandler() {
        @Override
        void onOpen(WebSocketWriter writer) {
            writer.close();
            // write after close is ignored.
            writer.write("foo");
        }
    };

    private static final WebSocketService webSocketService = WebSocketService.builder(handler)
                                                                             .closeTimeoutMillis(2000)
                                                                             .build();
    private HttpRequestWriter req;
    private ServiceRequestContext ctx;

    @BeforeEach
    void setUp() {
        req = HttpRequest.streaming(webSocketUpgradeHeaders());
        ctx = ServiceRequestContext.builder(req)
                                   .sessionProtocol(SessionProtocol.H1C)
                                   .build();
    }

    @Test
    void responseIsClosedRightAwayIfCloseFrameReceived() throws Exception {
        final ByteBuf encodedFrame =
                WebSocketFrameEncoder.of(true)
                                     .encode(ctx, WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE));
        req.write(HttpData.wrap(encodedFrame));
        final HttpResponse response = webSocketService.serve(ctx, req);
        final HttpResponseSubscriber httpResponseSubscriber = new HttpResponseSubscriber();
        response.subscribe(httpResponseSubscriber);
        httpResponseSubscriber.whenComplete.join();
        checkCloseFrame(httpResponseSubscriber.messageQueue.poll(3, TimeUnit.SECONDS));
    }

    static void checkCloseFrame(HttpData httpData) throws InterruptedException {
        // 0 ~ 3 FIN, RSV1, RSV2, RSV3. 4 ~ 7 opcode
        assertThat(httpData.array()[0] & 0x0F).isEqualTo(WebSocketFrameType.CLOSE.opcode());
    }

    @Test
    void responseIsClosedAfterCloseTimeoutIfCloseFrameNotReceived() throws Exception {
        final HttpResponse response = webSocketService.serve(ctx, req);
        final HttpResponseSubscriber httpResponseSubscriber = new HttpResponseSubscriber();
        response.subscribe(httpResponseSubscriber);
        // 0 ~ 3 FIN, RSV1, RSV2, RSV3. 4 ~ 7 opcode
        checkCloseFrame(httpResponseSubscriber.messageQueue.poll(3, TimeUnit.SECONDS));
        final CompletableFuture<Void> whenComplete = httpResponseSubscriber.whenComplete;
        assertThat(whenComplete.isDone()).isFalse();
        // response is complete 2000 milliseconds after the service sends the close frame.
        await().atLeast(1500 /* buffer 500 milliseconds */, TimeUnit.MILLISECONDS)
               .until(whenComplete::isDone);
        assertThat(whenComplete.isCompletedExceptionally()).isFalse();
    }

    private static RequestHeaders webSocketUpgradeHeaders() {
        return RequestHeaders.builder(HttpMethod.GET, "/chat")
                             .add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE.toString())
                             .add(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toString())
                             .addInt(HttpHeaderNames.SEC_WEBSOCKET_VERSION, 13)
                             .add(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
                             .add(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "superchat")
                             .build();
    }

    static class AbstractWebSocketHandler implements WebSocketHandler {

        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket messages) {
            final WebSocketWriter writer = WebSocket.streaming();
            messages.subscribe(new Subscriber<WebSocketFrame>() {
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
                                onMessage(writer, ((TextWebSocketFrame) frame).text());
                                break;
                            case BINARY:
                                onMessage(writer, frame.byteBuf(ByteBufAccessMode.RETAINED_DUPLICATE));
                                break;
                            case CLOSE:
                                assert frame instanceof CloseWebSocketFrame;
                                final CloseWebSocketFrame closeWebSocketFrame =
                                        (CloseWebSocketFrame) frame;
                                onClose(writer, closeWebSocketFrame.status(), closeWebSocketFrame.reason());
                                break;
                            default:
                                // no-op
                        }
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

        void onMessage(WebSocketWriter writer, String message) {}

        void onMessage(WebSocketWriter writer, ByteBuf message) {
            try {
                if (message.hasArray()) {
                    onMessage(writer, message);
                } else {
                    onMessage(writer, ByteBufUtil.getBytes(message));
                }
            } finally {
                message.release();
            }
        }

        void onMessage(WebSocketWriter writer, byte[] message) {}

        void onClose(WebSocketWriter writer, WebSocketCloseStatus status, String reason) {
            writer.close(status, reason);
        }
    }

    static final class HttpResponseSubscriber implements Subscriber<HttpObject> {

        final CompletableFuture<Void> whenComplete = new CompletableFuture<>();

        final BlockingQueue<HttpData> messageQueue = new LinkedBlockingQueue<>();

        @Override
        public void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpObject httpObject) {
            if (httpObject instanceof HttpData) {
                messageQueue.add((HttpData) httpObject);
            }
        }

        @Override
        public void onError(Throwable t) {
            whenComplete.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            whenComplete.complete(null);
        }
    }
}
