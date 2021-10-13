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
package com.linecorp.armeria.it.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.BinaryFrame;
import org.java_websocket.framing.ContinuousFrame;
import org.java_websocket.framing.Framedata;
import org.java_websocket.framing.PingFrame;
import org.java_websocket.framing.TextFrame;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketFrameType;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.websocket.WebSocketHandler;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebSocketServiceItTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/chat", WebSocketService.of(new WebSocketEchoHandler()));
        }
    };

    private JavaWebSocketClient client;

    @BeforeEach
    void setUp() throws InterruptedException, URISyntaxException {
        final URI serverUri = new URI("ws://127.0.0.1:" + server.httpPort() + "/chat");
        client = new JavaWebSocketClient(serverUri);
        client.connectBlocking();
    }

    @Test
    void echoText() throws Exception {
        final List<String> sendingMessages = ImmutableList.of("foobar", Strings.repeat("abc", 3 * 1024), "baz");
        sendingMessages.forEach(client::send);

        for (String sendingMessage : sendingMessages) {
            assertThat(sendingMessage).isEqualTo(client.receivedMessages().take());
        }
        assertThat(client.receivedMessages()).isEmpty();
        client.close();
        await().until(() -> client.closeStatus().get() == WebSocketCloseStatus.NORMAL_CLOSURE.code());
    }

    @Test
    void echoBinary() throws Exception {
        final List<String> sendingMessages = ImmutableList.of("foobar", Strings.repeat("abc", 3 * 1024), "baz");
        sendingMessages.forEach(message -> client.send(message.getBytes(StandardCharsets.UTF_8)));

        for (String sendingMessage : sendingMessages) {
            assertThat(sendingMessage).isEqualTo(new String(client.binaryMessages.take().array()));
        }
        assertThat(client.binaryMessages()).isEmpty();
        client.close();
        await().until(() -> client.closeStatus().get() == WebSocketCloseStatus.NORMAL_CLOSURE.code());
    }

    @Test
    void exceedMaxFrameLength() throws Exception {
        client.send("foo");
        client.send(Strings.repeat("a", 64 * 1024));
        await().until(() -> client.closeStatus().get() == WebSocketCloseStatus.MESSAGE_TOO_BIG.code());
        assertThat(client.closeReason().get()).isEqualTo("Max frame length of 65535 has been exceeded.");
    }

    @Test
    void sendFragments() throws Exception {
        final List<String> sendingMessages = ImmutableList.of("foobar", Strings.repeat("abc", 3 * 1024), "baz");

        int i = 0;
        final TextFrame textFrame = new TextFrame();
        textFrame.setFin(false);
        textFrame.setPayload(ByteBuffer.wrap(sendingMessages.get(i).getBytes(StandardCharsets.UTF_8)));
        client.sendFrame(textFrame);
        // Can send ping between fragments.
        client.sendPing();
        i++;
        for (; i < 3; i++) {
            final ContinuousFrame continuousFrame = new ContinuousFrame();
            continuousFrame.setFin(i == 2 ? true : false);
            continuousFrame.setPayload(ByteBuffer.wrap(sendingMessages.get(i)
                                                                      .getBytes(StandardCharsets.UTF_8)));
            client.sendFrame(continuousFrame);
        }

        assertThat(String.join("", sendingMessages)).isEqualTo(client.receivedMessages().take());
        assertThat(client.receivedMessages()).isEmpty();
        client.close();
        await().until(() -> client.closeStatus().get() == WebSocketCloseStatus.NORMAL_CLOSURE.code());
    }

    @Test
    void wrongFragments() throws Exception {
        final TextFrame textFrame = new TextFrame();
        textFrame.setFin(false);
        textFrame.setPayload(ByteBuffer.wrap("foo".getBytes(StandardCharsets.UTF_8)));
        client.sendFrame(textFrame);

        final BinaryFrame binaryFrame = new BinaryFrame();
        binaryFrame.setFin(false);
        binaryFrame.setPayload(ByteBuffer.wrap("foo".getBytes(StandardCharsets.UTF_8)));
        client.sendFrame(binaryFrame);

        await().until(() -> client.closeStatus().get() == WebSocketCloseStatus.PROTOCOL_ERROR.code());
        assertThat(client.closeReason().get()).isEqualTo(
                "received non-continuation data frame while inside fragmented message");
    }

    @Test
    void pongSentByDefaultWhenPing() throws Exception {
        final PingFrame pingFrame = new PingFrame();
        final String pingMessage = "It's a ping";
        pingFrame.setPayload(ByteBuffer.wrap(pingMessage.getBytes(StandardCharsets.UTF_8)));
        client.sendFrame(pingFrame);

        assertThat(pingMessage).isEqualTo(client.receivedMessages().take());
        assertThat(client.receivedMessages()).isEmpty();

        client.close();
        await().until(() -> client.closeStatus().get() == WebSocketCloseStatus.NORMAL_CLOSURE.code());
    }

    private static final class JavaWebSocketClient extends WebSocketClient {

        private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
        private final BlockingQueue<ByteBuffer> binaryMessages = new LinkedBlockingQueue<>();
        private final AtomicInteger closeStatus = new AtomicInteger();
        private final AtomicReference<String> closeReason = new AtomicReference<>();

        JavaWebSocketClient(URI serverUri) {
            super(serverUri);
        }

        BlockingQueue<String> receivedMessages() {
            return receivedMessages;
        }

        BlockingQueue<ByteBuffer> binaryMessages() {
            return binaryMessages;
        }

        AtomicInteger closeStatus() {
            return closeStatus;
        }

        AtomicReference<String> closeReason() {
            return closeReason;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            assertThat(handshakedata.getHttpStatus()).isEqualTo((short) 101);
        }

        @Override
        public void onMessage(String message) {
            receivedMessages.add(message);
        }

        @Override
        public void onMessage(ByteBuffer bytes) {
            binaryMessages.add(bytes);
        }

        @Override
        public void onWebsocketPong(org.java_websocket.WebSocket conn, Framedata f) {
            final ByteBuffer payloadData = f.getPayloadData();
            if (payloadData.hasRemaining()) {
                receivedMessages.add(new String(payloadData.array()));
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closeReason.set(reason);
            closeStatus.set(code);
        }

        @Override
        public void onError(Exception ex) {}
    }

    static final class WebSocketEchoHandler implements WebSocketHandler {

        @Override
        public WebSocket handle(ServiceRequestContext ctx, WebSocket messages) {
            final WebSocketWriter writer = WebSocket.streaming();
            messages.subscribe(new Subscriber<WebSocketFrame>() {
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
