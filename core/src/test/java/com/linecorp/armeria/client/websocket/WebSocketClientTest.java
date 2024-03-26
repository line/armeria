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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.RequestHeaders;
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
        final CompletableFuture<WebSocketSession> future;
        if (defaultClient) {
            future = WebSocketClient.of().connect(server.uri(protocol, SerializationFormat.WS) + "/chat");
        } else {
            final WebSocketClient webSocketClient =
                    WebSocketClient.builder(server.uri(protocol, SerializationFormat.WS))
                                   .factory(ClientFactory.insecure())
                                   .build();
            future = webSocketClient.connect("/chat");
        }
        final WebSocketSession webSocketSession = future.join();
        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        final RequestHeaders headers = sctx.log().ensureAvailable(RequestLogProperty.REQUEST_HEADERS)
                                           .requestHeaders();
        assertThat(headers.get(HttpHeaderNames.ORIGIN)).isEqualTo(
                protocol.isHttps() ? server.httpsUri().toString() : server.httpUri().toString());

        final WebSocketWriter outbound = webSocketSession.outbound();
        outbound.write(WebSocketFrame.ofText("hello"));

        final WebSocketInboundTestHandler inboundHandler = new WebSocketInboundTestHandler(
                webSocketSession.inbound(), protocol);

        WebSocketFrame frame = inboundHandler.inboundQueue().take();
        assertThat(frame).isEqualTo(WebSocketFrame.ofText("hello"));

        frame = inboundHandler.inboundQueue().poll(1, TimeUnit.SECONDS);
        assertThat(frame).isNull();

        outbound.write(WebSocketFrame.ofText("armeria"));
        frame = inboundHandler.inboundQueue().take();
        assertThat(frame).isEqualTo(WebSocketFrame.ofText("armeria"));

        outbound.close(WebSocketCloseStatus.NORMAL_CLOSURE);
        frame = inboundHandler.inboundQueue().take();
        assertThat(frame).isEqualTo(WebSocketFrame.ofClose(WebSocketCloseStatus.NORMAL_CLOSURE));
        inboundHandler.completionFuture().join();
        await().until(outbound::isComplete);
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
}
