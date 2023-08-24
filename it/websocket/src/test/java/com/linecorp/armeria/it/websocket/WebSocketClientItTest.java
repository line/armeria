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

package com.linecorp.armeria.it.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;

class WebSocketClientItTest {

    private static final Queue<String> sentMessages = new ArrayBlockingQueue<>(2);

    @BeforeEach
    void setUp() {
        sentMessages.clear();
    }

    @CsvSource({ "h1c", "h2c" })
    @ParameterizedTest
    void webSocketClientIt(String protocol) throws Exception {
        final Server server = new Server();
        final ServerConnector connector = createConnector(protocol, server);
        server.addConnector(connector);
        setupJettyWebSocket(server);
        server.start();

        final WebSocketClient client = WebSocketClient.of(
                protocol + "://127.0.0.1:" + connector.getLocalPort());
        final WebSocketSession webSocketSession = client.connect("/chat").join();

        final WebSocketWriter writer = WebSocket.streaming();
        webSocketSession.setOutbound(writer);
        writer.write("Hello, world!");
        writer.write("bye");
        writer.close();

        final CountDownLatch latch = new CountDownLatch(1);
        final List<WebSocketFrame> frames = new ArrayList<>();
        webSocketSession.inbound().subscribe(
                new Subscriber<WebSocketFrame>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(WebSocketFrame webSocketFrame) {
                        frames.add(webSocketFrame);
                    }

                    @Override
                    public void onError(Throwable t) {
                        // The connection is closed by the server if HTTP/1.1
                        assertThat(t).isExactlyInstanceOf(ClosedSessionException.class);
                        latch.countDown();
                    }

                    @Override
                    public void onComplete() {
                        latch.countDown();
                    }
                });
        latch.await();
        assertThat(frames.size()).isOne();
        final WebSocketFrame frame = frames.get(0);
        assertThat(frame).isInstanceOf(CloseWebSocketFrame.class);
        assertThat(((CloseWebSocketFrame) frame).status()).isSameAs(WebSocketCloseStatus.NORMAL_CLOSURE);

        assertThat(sentMessages).containsExactly("Hello, world!", "bye");
        server.stop();
    }

    @CsvSource({
            "h1c, foo2, foo1, foo2",
            "h1c, bar1, bar2, ",
            "h2c, foo2, foo1, foo2",
            "h2c, bar1, bar2, "
    })
    @ParameterizedTest
    void subprotocol(String sessionProtocol,
                     String subprotocol1, String subprotocol2, @Nullable String selected) throws Exception {
        final Server server = new Server();
        final ServerConnector connector = createConnector(sessionProtocol, server);
        server.addConnector(connector);
        setupJettyWebSocket(server);
        server.start();

        final WebSocketClient client =
                WebSocketClient.builder(sessionProtocol + "://127.0.0.1:" + connector.getLocalPort())
                               .subprotocols(subprotocol1, subprotocol2)
                               .build();
        final WebSocketSession session = client.connect("/chat").join();
        if (selected == null) {
            assertThat(session.responseHeaders().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)).isNull();
        } else {
            assertThat(session.responseHeaders().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL))
                    .isEqualTo(selected);
        }
        // Abort the session to close the connection.
        final WebSocketWriter outbound = WebSocket.streaming();
        outbound.abort();
        session.setOutbound(outbound);
        session.inbound().abort();

        server.stop();
    }

    private static void setupJettyWebSocket(Server server) {
        final ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            // Add WebSocket endpoint
            wsContainer.addEndpoint(
                    ServerEndpointConfig.Builder.create(EventSocket.class, "/chat")
                                                .subprotocols(ImmutableList.of("foo", "foo1", "foo2"))
                                                .build());
        });
    }

    private static ServerConnector createConnector(String protocol, Server server) {
        if ("h1c".equals(protocol)) {
            return new ServerConnector(server);
        }
        return new ServerConnector(server, new HTTP2ServerConnectionFactory(new HttpConfiguration()));
    }

    @ServerEndpoint("/")
    public static class EventSocket {
        @OnOpen
        public void onWebSocketConnect(Session sess) {}

        @OnMessage
        public void onWebSocketText(Session sess, String message) throws IOException {
            sentMessages.add(message);
            if (message.toLowerCase(Locale.US).contains("bye")) {
                sess.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Thanks"));
            }
        }

        @OnClose
        public void onWebSocketClose(CloseReason reason) {}

        @OnError
        public void onWebSocketError(Throwable cause) {}
    }
}
