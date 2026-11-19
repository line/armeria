/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeMap;

class ClientConnectionReplacementTest {

    private static final long MAX_CONNECTION_AGE_MILLIS = 1500;
    // Shorter than half the max age, so that an HTTP/1 replacement, which is used only after the old
    // connection retires, serves requests before its own lead time.
    private static final long LEAD_TIME_MILLIS = 400;
    private static final long REPLACEMENT_DUE_MILLIS = MAX_CONNECTION_AGE_MILLIS - LEAD_TIME_MILLIS;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.service("/ws", WebSocketService.of((ctx, in) -> {
                in.subscribe(NoopSubscriber.get());
                return WebSocket.streaming();
            }));
        }
    };

    @RegisterExtension
    static final ServerExtension oneStreamServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.http2MaxStreamsPerConnection(1);
            sb.service("/slow", (ctx, req) -> HttpResponse.delayed(
                    HttpResponse.of(HttpStatus.OK), Duration.ofMillis(MAX_CONNECTION_AGE_MILLIS + 500)));
        }
    };

    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H1", "H2C", "H2" })
    @ParameterizedTest
    void steadyTrafficNeverWaitsForAConnection(SessionProtocol protocol) throws Exception {
        final ConnectionEvents connections = new ConnectionEvents();
        try (ClientFactory factory = factoryBuilder(connections).build()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(factory).build();
            final List<Integer> requestsThatWaited =
                    sendSteadyRequests(client, 2 * MAX_CONNECTION_AGE_MILLIS + 1000);

            assertThat(connections.closed())
                    .as("connections must still rotate at the max age")
                    .isGreaterThanOrEqualTo(2);
            assertThat(requestsThatWaited)
                    .as("only the very first request may wait for a connection to be established")
                    .containsExactly(0);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H1", "H2C", "H2" })
    @ParameterizedTest
    void slowReplacementDoesNotDelayTheMaxAge(SessionProtocol protocol) throws Exception {
        final ConnectionEvents connections = new ConnectionEvents();
        // The replacement connects long after the old connection reaches its max age.
        final ClientFactoryBuilder builder = factoryBuilder(connections).option(
                ClientFactoryOptions.CHANNEL_PIPELINE_CUSTOMIZER,
                onSecondChannel(pipeline -> pipeline.addFirst(
                        new SlowConnectHandler(2 * MAX_CONNECTION_AGE_MILLIS))));
        try (ClientFactory factory = builder.build()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(factory).build();
            while (connections.closed() == 0) {
                assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
                Thread.sleep(20);
            }

            await().untilAsserted(() -> assertThat(connections.opened()).isEqualTo(2));
            final List<ConnectionEvent> events = connections.events();
            final int first = events.get(0).localPort;
            final int replacement = events.get(2).localPort;
            assertThat(events)
                    .extracting(ConnectionEvent::toString)
                    .as("the old connection closes at its max age without waiting for its replacement")
                    .startsWith("opened " + first, "closed " + first, "opened " + replacement);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    @ParameterizedTest
    void idleConnectionIsNotReplaced(SessionProtocol protocol) {
        final ConnectionEvents connections = new ConnectionEvents();
        try (ClientFactory factory = factoryBuilder(connections).build()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(factory).build();
            // Without an idle timeout, a request counts as recent only within the lead time.
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);

            await().untilAsserted(() -> assertThat(connections.closed()).isEqualTo(1));
            assertThat(connections.opened())
                    .as("a connection without recent requests closes at its max age without a replacement")
                    .isEqualTo(1);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    @ParameterizedTest
    void connectionUsedWithinIdleTimeoutIsReplaced(SessionProtocol protocol) {
        final ConnectionEvents connections = new ConnectionEvents();
        // The idle timeout is clamped to the max age, so the only request counts as recent until then.
        final ClientFactoryBuilder builder =
                factoryBuilder(connections).idleTimeoutMillis(MAX_CONNECTION_AGE_MILLIS);
        try (ClientFactory factory = builder.build()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(factory).build();
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);

            await().untilAsserted(() -> assertThat(connections.closed()).isGreaterThanOrEqualTo(1));
            final List<ConnectionEvent> events = connections.events();
            final int first = events.get(0).localPort;
            assertThat(events)
                    .extracting(ConnectionEvent::toString)
                    .as("a connection used within the idle timeout is replaced before it closes")
                    .startsWith("opened " + first, "opened " + events.get(1).localPort, "closed " + first);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H2C", "H2" })
    @ParameterizedTest
    void connectionsAgingTogetherShareOneReplacement(SessionProtocol protocol) {
        final ConnectionEvents connections = new ConnectionEvents();
        // Keep the first replacement connecting while the second connection reaches its lead time.
        final AtomicInteger numChannels = new AtomicInteger();
        final ClientFactoryBuilder builder = factoryBuilder(connections).option(
                ClientFactoryOptions.CHANNEL_PIPELINE_CUSTOMIZER, pipeline -> {
                    if (numChannels.incrementAndGet() > 2) {
                        pipeline.addFirst(new SlowConnectHandler(LEAD_TIME_MILLIS));
                    }
                });
        try (ClientFactory factory = builder.build()) {
            final WebClient client = WebClient.builder(oneStreamServer.uri(protocol)).factory(factory).build();
            // The server allows one stream per connection, so two requests in progress open two connections
            // that reach their lead time together.
            final CompletableFuture<AggregatedHttpResponse> res1 = client.get("/slow").aggregate();
            final CompletableFuture<AggregatedHttpResponse> res2 = client.get("/slow").aggregate();
            assertThat(res1.join().status()).isEqualTo(HttpStatus.OK);
            assertThat(res2.join().status()).isEqualTo(HttpStatus.OK);

            await().untilAsserted(() -> assertThat(connections.closed()).isEqualTo(2));
            assertThat(connections.opened())
                    .as("the second connection shares the replacement that the first one started")
                    .isEqualTo(3);
        }
    }

    @Test
    void webSocketConnectionIsNotReplaced() {
        final ConnectionEvents connections = new ConnectionEvents();
        try (ClientFactory factory = factoryBuilder(connections).build()) {
            final WebSocketSession session =
                    WebSocketClient.builder(server.uri(SessionProtocol.H2C, SerializationFormat.WS))
                                   .factory(factory)
                                   .build()
                                   .connect("/ws")
                                   .join();
            try {
                // The WebSocket stream stays in progress, so only the WebSocket check skips the replacement.
                await().during(Duration.ofMillis(REPLACEMENT_DUE_MILLIS + 300))
                       .atMost(Duration.ofMillis(REPLACEMENT_DUE_MILLIS + 3000))
                       .untilAsserted(() -> assertThat(connections.opened()).isEqualTo(1));
            } finally {
                session.outbound().close();
                session.inbound().abort();
            }
        }
    }

    private static ClientFactoryBuilder factoryBuilder(ConnectionPoolListener listener) {
        return ClientFactory.builder()
                            .connectionPoolListener(listener)
                            .idleTimeoutMillis(0)
                            .maxConnectionAge(MaxConnectionAgeConfig.builder(MAX_CONNECTION_AGE_MILLIS)
                                                                    .replacementLeadTimeMillis(LEAD_TIME_MILLIS)
                                                                    .build())
                            .tlsNoVerify();
    }

    /**
     * Sends a request every 20 milliseconds and returns the indexes of the requests that waited for
     * a connection to be established instead of using a pooled one.
     */
    private static List<Integer> sendSteadyRequests(WebClient client, long durationMillis)
            throws InterruptedException {
        final List<Integer> requestsThatWaited = new ArrayList<>();
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
        for (int i = 0; System.nanoTime() < deadlineNanos; i++) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
                ctx = captor.get();
            }
            if (ctx.log().whenComplete().join().connectionTimings() != null) {
                requestsThatWaited.add(i);
            }
            Thread.sleep(20);
        }
        return requestsThatWaited;
    }

    private static Consumer<ChannelPipeline> onSecondChannel(Consumer<ChannelPipeline> customizer) {
        final AtomicInteger numChannels = new AtomicInteger();
        return pipeline -> {
            if (numChannels.incrementAndGet() == 2) {
                customizer.accept(pipeline);
            }
        };
    }

    private static final class SlowConnectHandler extends ChannelOutboundHandlerAdapter {

        private final long delayMillis;

        SlowConnectHandler(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
                            SocketAddress localAddress, ChannelPromise promise) {
            ctx.executor().schedule(() -> ctx.connect(remoteAddress, localAddress, promise),
                                    delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private static final class ConnectionEvents implements ConnectionPoolListener {

        private final List<ConnectionEvent> events = new CopyOnWriteArrayList<>();

        List<ConnectionEvent> events() {
            return new ArrayList<>(events);
        }

        long opened() {
            return events.stream().filter(e -> e.open).count();
        }

        long closed() {
            return events.stream().filter(e -> !e.open).count();
        }

        @Override
        public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                   InetSocketAddress localAddr, AttributeMap attrs) {
            events.add(new ConnectionEvent(true, localAddr.getPort()));
        }

        @Override
        public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                     InetSocketAddress localAddr, AttributeMap attrs) {
            events.add(new ConnectionEvent(false, localAddr.getPort()));
        }
    }

    private static final class ConnectionEvent {

        final boolean open;
        final int localPort;

        ConnectionEvent(boolean open, int localPort) {
            this.open = open;
            this.localPort = localPort;
        }

        @Override
        public String toString() {
            return (open ? "opened " : "closed ") + localPort;
        }
    }
}
