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

package com.linecorp.armeria.testing.junit5.server;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.server.websocket.WebSocketServiceHandler;

class ServerExtensionWithWebSocketClientTest {

    @RegisterExtension
    static ServerExtension wsServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/chat", WebSocketService.builder(new WebSocketEchoHandler())
                                                .build());
        }
    };

    @RegisterExtension
    static ServerExtension wssServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tlsSelfSigned();
            sb.service("/chat", WebSocketService.builder(new WebSocketEchoHandler())
                                                .build());
        }
    };

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void webSocketClient(boolean useTls) {
        final WebSocketClient webSocketClient = useTls ? wssServer.webSocketClient()
                                                       : wsServer.webSocketClient();
        final WebSocketSession wsSession = webSocketClient.connect("/chat").join();
        assertThat(wsSession).isNotNull();
        final WebSocketWriter outbound = wsSession.outbound();
        outbound.write("hello");
        final String message = useTls ? "wss" : "ws";
        outbound.write(message);
        outbound.close();
        final List<String> responses = wsSession.inbound().collect().join().stream().map(WebSocketFrame::text)
                                                .collect(toImmutableList());
        assertThat(responses).contains("hello", message);
    }

    static final class WebSocketEchoHandler implements WebSocketServiceHandler {

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
                    writer.write(webSocketFrame);
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
