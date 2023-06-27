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

package com.linecorp.armeria.client.websocket;

import static com.linecorp.armeria.client.websocket.DefaultWebSocketClient.generateSecWebSocketKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.websocket.WebSocketSession.WebSocketCallbackHandler;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http.HttpHeaderValues;

class WebSocketClientTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0)
              .https(0)
              .tlsSelfSigned();
            sb.route()
              .get("/chat")
              .connect("/chat")
              .requestAutoAbortDelayMillis(5000)
              .build(WebSocketService.of(new WebSocketServiceEchoHandler()));
        }
    };

    @CsvSource({
            "H1,    false",
            "H1C,   true",
            "H1C,   false",
            "H2,    false",
            "H2C,   true",
            "H2C,   false",
            "HTTP,  true",
            "HTTP,  false",
            "HTTPS, false"
    })
    @ParameterizedTest
    void webSocketClient(SessionProtocol protocol, boolean defaultClient) throws InterruptedException {
        // TODO(minwoox): Add server.webSocketClient();
        final WebSocketQueueHandler handler = new WebSocketQueueHandler();
        if (defaultClient) {
            WebSocketClient.of().connect(
                    server.uri(protocol, SerializationFormat.WS) + "/chat", handler);
        } else {
            final WebSocketClient webSocketClient =
                    WebSocketClient.builder(server.uri(protocol, SerializationFormat.WS))
                                   .factory(ClientFactory.insecure())
                                   .build();
            webSocketClient.connect("/chat", handler);
        }
        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        final RequestHeaders headers = sctx.log().ensureAvailable(RequestLogProperty.REQUEST_HEADERS)
                                           .requestHeaders();
        assertThat(headers.get(HttpHeaderNames.ORIGIN)).isEqualTo(
                protocol.isHttps() ? server.httpsUri().toString() : server.httpUri().toString());

        handler.outbound().write(WebSocketFrame.ofText("hello"));
        WebSocketFrame frame = handler.inboundQueue().take();
        assertThat(frame).isEqualTo(WebSocketFrame.ofText("hello"));

        frame = handler.inboundQueue().poll(1, TimeUnit.SECONDS);
        assertThat(frame).isNull();

        handler.outbound().write(WebSocketFrame.ofText("armeria"));
        frame = handler.inboundQueue().take();
        assertThat(frame).isEqualTo(WebSocketFrame.ofText("armeria"));

        handler.outbound().close(WebSocketCloseStatus.NORMAL_CLOSURE);
        frame = handler.inboundQueue().take();
        assertThat(frame).isEqualTo(WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE));
        handler.completionFuture().join();
        assertThat(handler.outbound().isComplete()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({ "H2", "H2C" })
    void useHttp2Headers(SessionProtocol protocol) {
        final WebSocketClient webSocketClient =
                WebSocketClient.builder(server.uri(protocol, SerializationFormat.WS))
                               .factory(ClientFactory.insecure())
                               .build();
        final RequestHeadersBuilder builder = RequestHeaders.builder(HttpMethod.CONNECT, "/chat");

        assertThatThrownBy(() -> webSocketClient.connect(builder.build(), new WebSocketQueueHandler()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid WebSocket request headers: ");

        final WebSocketQueueHandler handler = new WebSocketQueueHandler();
        builder.set(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString())
               .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        webSocketClient.connect(builder.build(), handler);
        handler.outbound().close(WebSocketCloseStatus.NORMAL_CLOSURE);
        handler.completionFuture().join();
        assertThat(handler.inboundQueue().poll()).isEqualTo(
                WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE));
    }

    @ParameterizedTest
    @CsvSource({ "H1", "H1C" })
    void useHttp1Headers(SessionProtocol protocol) {
        final WebSocketClient webSocketClient =
                WebSocketClient.builder(server.uri(protocol, SerializationFormat.WS))
                               .factory(ClientFactory.insecure())
                               .build();
        final RequestHeadersBuilder builder = RequestHeaders.builder(HttpMethod.GET, "/chat");
        assertThatThrownBy(() -> webSocketClient.connect(builder.build(), new WebSocketQueueHandler()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid WebSocket request headers: ");

        final WebSocketQueueHandler handler = new WebSocketQueueHandler();
        builder.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE.toString())
               .set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toString())
               .set(HttpHeaderNames.SEC_WEBSOCKET_KEY, generateSecWebSocketKey())
               .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        webSocketClient.connect(builder.build(), handler);
        handler.outbound().close(WebSocketCloseStatus.NORMAL_CLOSURE);
        handler.completionFuture().join();
        assertThat(handler.inboundQueue().poll()).isEqualTo(
                WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE));
    }

    @ParameterizedTest
    @CsvSource({ "HTTP", "HTTPS" })
    void http1HeadersAreChangedIfHttp2Available(SessionProtocol protocol) throws InterruptedException {
        final WebSocketClient webSocketClient =
                WebSocketClient.builder(server.uri(protocol, SerializationFormat.WS))
                               .factory(ClientFactory.insecure())
                               .build();
        final RequestHeadersBuilder builder = RequestHeaders.builder(HttpMethod.GET, "/chat");
        assertThatThrownBy(() -> webSocketClient.connect(builder.build(), new WebSocketQueueHandler()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid WebSocket request headers: ");

        final WebSocketQueueHandler handler = new WebSocketQueueHandler();
        builder.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE.toString())
               .set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toString())
               .set(HttpHeaderNames.SEC_WEBSOCKET_KEY, generateSecWebSocketKey())
               .set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        webSocketClient.connect(builder.build(), handler);
        handler.outbound().close(WebSocketCloseStatus.NORMAL_CLOSURE);
        handler.completionFuture().join();
        assertThat(handler.inboundQueue().poll()).isEqualTo(
                WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE));

        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        final SessionProtocol actualProtocol = sctx.log().ensureAvailable(RequestLogProperty.SESSION)
                                                   .sessionProtocol();
        assertThat(actualProtocol.isExplicitHttp2()).isTrue();

        final RequestHeaders requestHeaders = sctx.log().ensureAvailable(RequestLogProperty.REQUEST_HEADERS)
                                                  .requestHeaders();
        assertThat(requestHeaders.method()).isSameAs(HttpMethod.CONNECT);
        assertThat(requestHeaders.get(HttpHeaderNames.PROTOCOL)).isEqualTo("websocket");
        assertThat(requestHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_VERSION)).isEqualTo("13");
        assertThat(requestHeaders.get(HttpHeaderNames.CONNECTION)).isNull();
        assertThat(requestHeaders.get(HttpHeaderNames.UPGRADE)).isNull();
        assertThat(requestHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_KEY)).isNull();
        assertThat(requestHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_KEY)).isNull();
    }

    static final class WebSocketQueueHandler implements WebSocketClientHandler {

        private final ArrayBlockingQueue<WebSocketFrame> inboundQueue = new ArrayBlockingQueue<>(4);
        private final WebSocketWriter outbound = WebSocket.streaming();
        private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        ArrayBlockingQueue<WebSocketFrame> inboundQueue() {
            return inboundQueue;
        }

        WebSocketWriter outbound() {
            return outbound;
        }

        CompletableFuture<Void> completionFuture() {
            return completionFuture;
        }

        @Override
        public WebSocket handle(ClientRequestContext ctx, WebSocket in) {
            in.subscribe(new Subscriber<WebSocketFrame>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketFrame webSocketFrame) {
                    inboundQueue.add(webSocketFrame);
                }

                @Override
                public void onError(Throwable t) {
                    completionFuture.completeExceptionally(t);
                }

                @Override
                public void onComplete() {
                    completionFuture.complete(null);
                }
            });
            return outbound;
        }
    }

    static final class WebSocketServiceEchoHandler implements WebSocketServiceHandler {

        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
            final WebSocketWriter writer = WebSocket.streaming();
            in.subscribe(new Subscriber<WebSocketFrame>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketFrame webSocketFrame) {
                    if (webSocketFrame.type() != WebSocketFrameType.PING &&
                        webSocketFrame.type() != WebSocketFrameType.PONG) {
                        writer.write(webSocketFrame);
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
    }

    @Test
    void foo() {
        final WebSocketClient client = WebSocketClient.of();
        final CompletableFuture<WebSocketSession> future = client.connect("/chat");
        final WebSocketSession session = future.join();

        final WebSocket inboundMessages = session.inbound();
//        final WebSocketWriter outboundMessages = session.outboundMessages();
        final String subprotocol = session.subprotocol();

        final WebSocketCallbackHandler handler = new WebSocketCallbackHandler() {};
        session.start(handler);

        future.handle((webSocketSession, cause) -> {
            if (cause != null) {
                // Handshake failure.
                return null;
            }
            final String subprotocol1 = webSocketSession.subprotocol();
            webSocketSession.start(handler);

            // Handle success.
            return null;
        });
    }

}
