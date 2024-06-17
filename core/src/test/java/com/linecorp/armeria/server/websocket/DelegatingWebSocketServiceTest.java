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

package com.linecorp.armeria.server.websocket;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DelegatingWebSocketServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final WebSocketService delegate =
                    WebSocketService
                            .builder(new EchoWebSocketHandler())
                            .fallbackService((ctx, req) -> HttpResponse.of("fallback"))
                            .build();

            sb.service("/ws-or-http", new DelegatingWebSocketService(delegate));
        }
    };

    @Test
    void shouldReturnMessageInUpperCase() {
        final WebSocketClient client = WebSocketClient.of(server.httpUri());
        final WebSocketSession session = client.connect("/ws-or-http").join();
        final WebSocketWriter outbound = session.outbound();
        outbound.write("hello");
        outbound.write("world");
        outbound.close();
        final List<String> responses = session.inbound().collect().join().stream().map(WebSocketFrame::text)
                                              .collect(toImmutableList());
        assertThat(responses).contains("HELLO", "WORLD");
    }

    @Test
    void shouldReturnFallbackResponse() {
        final BlockingWebClient client = server.blockingWebClient();
        AggregatedHttpResponse response = client.get("/ws-or-http");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("fallback");
        response = client.post("/ws-or-http", "");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("fallback");
    }

    private static class EchoWebSocketHandler implements WebSocketServiceHandler {

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

    private static class DelegatingWebSocketService implements WebSocketService {

        private final WebSocketService delegate;

        DelegatingWebSocketService(WebSocketService delegate) {
            this.delegate = delegate;
        }

        @Override
        public WebSocket serve(ServiceRequestContext ctx, WebSocket in) throws Exception {
            final StreamMessage<WebSocketFrame> transformed = in.map(frame -> {
                if (frame.type() == WebSocketFrameType.TEXT) {
                    final String text = frame.text();
                    return WebSocketFrame.ofText(text.toUpperCase());
                }
                return frame;
            });
            return delegate.serve(ctx, WebSocket.of(transformed));
        }

        @Override
        public WebSocketProtocolHandler protocolHandler() {
            return delegate.protocolHandler();
        }
    }
}
