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

package com.linecorp.armeria.server.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http.HttpHeaderValues;

class WebSocketServiceCorsTest {

    @RegisterExtension
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.route()
              .path("/chat")
              .build(WebSocketService.builder(new CustomWebSocketServiceHandler())
                                     .allowedOrigins("*")
                                     .build());
        }
    };

    @RegisterExtension
    static final ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.route()
              .path("/chat")
              .build(WebSocketService.builder(new CustomWebSocketServiceHandler())
                                     .allowedOrigins("http://armeria.com")
                                     .build());
        }
    };

    @RegisterExtension
    static final ServerExtension server3 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.route()
              .path("/chat")
              .build(WebSocketService.builder(new CustomWebSocketServiceHandler())
                                     .allowedOrigin(origin -> origin.contains("armeria"))
                                     .build());
        }
    };

    @Test
    void testWhenAllOriginsAreAllowed() {
        final WebClient client = WebClient.builder(server1.uri(SessionProtocol.H1C))
                                          .responseTimeoutMillis(0)
                                          .build();
        assertThat(sendRequestAndRetrieveResponseHeaders(client, "http://armeria.com").status())
                .isEqualTo(HttpStatus.SWITCHING_PROTOCOLS);
        assertThat(sendRequestAndRetrieveResponseHeaders(client, "http://line.com").status())
                .isEqualTo(HttpStatus.SWITCHING_PROTOCOLS);
    }

    @Test
    void testWhenAllowedOriginsAreMatchedByString() {
        final WebClient client = WebClient.builder(server2.uri(SessionProtocol.H1C))
                                          .responseTimeoutMillis(0)
                                          .build();
        assertThat(sendRequestAndRetrieveResponseHeaders(client, "http://armeria.com").status())
                .isEqualTo(HttpStatus.SWITCHING_PROTOCOLS);
        assertThat(sendRequestAndRetrieveResponseHeaders(client, "http://line.com").status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void caseInsensitiveOrigin() {
        final WebClient client = WebClient.builder(server2.uri(SessionProtocol.H1C))
                                          .responseTimeoutMillis(0)
                                          .build();
        assertThat(sendRequestAndRetrieveResponseHeaders(client, "http://ARMERIA.com").status())
                .isEqualTo(HttpStatus.SWITCHING_PROTOCOLS);
    }

    @Test
    void testWhenAllowedOriginsAreMatchedByPredicate() {
        final WebClient client = WebClient.builder(server3.uri(SessionProtocol.H1C))
                                          .responseTimeoutMillis(0)
                                          .build();
        assertThat(sendRequestAndRetrieveResponseHeaders(client, "http://armeria.com").status())
                .isEqualTo(HttpStatus.SWITCHING_PROTOCOLS);
        assertThat(sendRequestAndRetrieveResponseHeaders(client, "http://armeria2.com").status())
                .isEqualTo(HttpStatus.SWITCHING_PROTOCOLS);
        assertThat(sendRequestAndRetrieveResponseHeaders(client, "http://line.com").status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static ResponseHeaders sendRequestAndRetrieveResponseHeaders(WebClient client, String origin) {
        final RequestHeaders requestHeaders = webSocketUpgradeHeaders(origin);
        final CompletableFuture<ResponseHeaders> informationalHeadersFuture = new CompletableFuture<>();
        client.execute(requestHeaders).subscribe(new WebSocketClientSubscriber(informationalHeadersFuture));
        return informationalHeadersFuture.join();
    }

    private static RequestHeaders webSocketUpgradeHeaders(String origin) {
        return RequestHeaders.builder(HttpMethod.GET, "/chat")
                             .add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE.toString())
                             .add(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toString())
                             .add(HttpHeaderNames.HOST, "foo.com")
                             .add(HttpHeaderNames.ORIGIN, origin)
                             .addInt(HttpHeaderNames.SEC_WEBSOCKET_VERSION, 13)
                             .add(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
                             .add(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "superchat")
                             .build();
    }

    static final class CustomWebSocketServiceHandler implements WebSocketServiceHandler {

        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket in) {
            final WebSocketWriter webSocketWriter = WebSocket.streaming();
            in.subscribe(new Subscriber<WebSocketFrame>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(WebSocketFrame webSocketFrame) {
                    try (WebSocketFrame frame = webSocketFrame) {
                        switch (frame.type()) {
                            case TEXT:
                                webSocketWriter.write(frame.text());
                                break;
                            case BINARY:
                                break;
                            case CLOSE:
                                final CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
                                webSocketWriter.close(closeFrame.status(), closeFrame.reasonPhrase());
                                break;
                            default:
                        }
                    } catch (Throwable t) {
                        webSocketWriter.close(t);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    webSocketWriter.close(t);
                }

                @Override
                public void onComplete() {
                    webSocketWriter.close();
                }
            });
            return webSocketWriter;
        }
    }

    static final class WebSocketClientSubscriber implements Subscriber<HttpObject> {

        private final CompletableFuture<ResponseHeaders> informationalHeadersFuture;

        WebSocketClientSubscriber(CompletableFuture<ResponseHeaders> informationalHeadersFuture) {
            this.informationalHeadersFuture = informationalHeadersFuture;
        }

        @Override
        public void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpObject httpObject) {
            informationalHeadersFuture.complete((ResponseHeaders) httpObject);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    }
}
