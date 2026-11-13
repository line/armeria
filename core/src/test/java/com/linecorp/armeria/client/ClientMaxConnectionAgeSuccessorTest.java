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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeMap;
import io.netty.util.ReferenceCountUtil;

class ClientMaxConnectionAgeSuccessorTest {

    private static final long MAX_CONNECTION_AGE_MILLIS = 1000;
    private static final long STEADY_TRAFFIC_MILLIS = 3 * MAX_CONNECTION_AGE_MILLIS + 500;
    private static final long SLOW_CONNECT_MILLIS = MAX_CONNECTION_AGE_MILLIS / 2;
    private static final long STILL_CONNECTING_AT_MAX_AGE_MILLIS =
            MAX_CONNECTION_AGE_MILLIS - SLOW_CONNECT_MILLIS / 2;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.service("/method", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(unused -> HttpResponse.of(ctx.method().name()))));
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
            sb.service("/", (ctx, req) -> HttpResponse.delayed(HttpResponse.of(HttpStatus.OK),
                                                                Duration.ofMillis(100)));
            sb.service("/slow", (ctx, req) -> HttpResponse.delayed(
                    HttpResponse.of(HttpStatus.OK),
                    Duration.ofMillis(MAX_CONNECTION_AGE_MILLIS + SLOW_CONNECT_MILLIS)));
        }
    };

    @EnumSource(value = SessionProtocol.class, names = { "H2C", "H2" })
    @ParameterizedTest
    void steadyTrafficNeverWaitsForAReplacementConnection(SessionProtocol protocol) throws Exception {
        final CountingConnectionPoolListener connections = new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(connections)
                                                  .idleTimeoutMillis(0)
                                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE_MILLIS)
                                                  .replaceConnectionOnMaxAge(true)
                                                  .tlsNoVerify()
                                                  .build()) {
            final List<Integer> requestsThatWaitedForAConnection =
                    indexesOfSteadyRequestsThatWaitedForAConnection(factory, protocol);

            assertThat(connections.opened())
                    .as("connections must still rotate at the max age")
                    .isGreaterThanOrEqualTo(3);
            assertThat(requestsThatWaitedForAConnection)
                    .as("only the very first request may wait for a connection to be established")
                    .containsExactly(0);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H2C", "H2" })
    @ParameterizedTest
    void successorThatServesNoRequestIsNotReplaced(SessionProtocol protocol) throws Exception {
        final ConnectionEvents connections = new ConnectionEvents();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(connections)
                                                  .idleTimeoutMillis(0)
                                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE_MILLIS)
                                                  .replaceConnectionOnMaxAge(true)
                                                  .tlsNoVerify()
                                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(protocol))
                                              .factory(factory)
                                              .build();
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);

            await().untilAsserted(() -> assertThat(connections.events()).hasSize(4));
            Thread.sleep(2 * MAX_CONNECTION_AGE_MILLIS);

            final List<ConnectionEvent> events = connections.events();
            final int first = events.get(0).localPort;
            final int successor = events.get(1).localPort;
            assertThat(successor).isNotEqualTo(first);
            assertThat(events)
                    .extracting(ConnectionEvent::toString)
                    .as("the used connection closes only after its successor opens, and the unused " +
                        "successor closes at its own max age without a successor of its own")
                    .containsExactly("opened " + first, "opened " + successor,
                                     "closed " + first, "closed " + successor);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H2C", "H2" })
    @ParameterizedTest
    void failedSuccessorFallsBackToConnectingOnDemand(SessionProtocol protocol) throws Exception {
        final CountingConnectionPoolListener connections = new CountingConnectionPoolListener();
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .connectionPoolListener(connections)
                                  .idleTimeoutMillis(0)
                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE_MILLIS)
                                  .replaceConnectionOnMaxAge(true)
                                  .option(ClientFactoryOptions.CHANNEL_PIPELINE_CUSTOMIZER,
                                          onSecondChannel(pipeline -> pipeline.addFirst(
                                                  new CloseOnActiveHandler())))
                                  .tlsNoVerify()
                                  .build()) {
            final List<Integer> requestsThatWaitedForAConnection =
                    indexesOfSteadyRequestsThatWaitedForAConnection(factory, protocol);

            assertThat(requestsThatWaitedForAConnection)
                    .as("only the first request after the first connection retires waits, as without " +
                        "a successor")
                    .hasSize(2)
                    .startsWith(0);
            assertThat(connections.opened())
                    .as("the connection made on demand still gets a successor")
                    .isGreaterThanOrEqualTo(3);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H2C", "H2" })
    @ParameterizedTest
    void stalledSuccessorStillRetiresTheOldConnection(SessionProtocol protocol) throws Exception {
        final long connectTimeoutMillis = 1000;
        final ConnectionEvents connections = new ConnectionEvents();
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .connectionPoolListener(connections)
                                  .idleTimeoutMillis(0)
                                  .connectTimeoutMillis(connectTimeoutMillis)
                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE_MILLIS)
                                  .replaceConnectionOnMaxAge(true)
                                  .option(ClientFactoryOptions.CHANNEL_PIPELINE_CUSTOMIZER,
                                          onSecondChannel(pipeline -> pipeline.addFirst(
                                                  new DropReadsHandler())))
                                  .tlsNoVerify()
                                  .build()) {
            final List<Integer> requestsThatWaitedForAConnection =
                    indexesOfSteadyRequestsThatWaitedForAConnection(factory, protocol);

            assertThat(requestsThatWaitedForAConnection)
                    .as("no request waits while the successor is stalled; only the first request after " +
                        "the old connection retires does")
                    .hasSize(2)
                    .startsWith(0);

            final List<ConnectionEvent> events = connections.events();
            final ConnectionEvent firstOpened = events.get(0);
            final ConnectionEvent firstClosed =
                    events.stream()
                          .filter(e -> !e.open && e.localPort == firstOpened.localPort)
                          .findFirst()
                          .orElseThrow(() -> new AssertionError("first connection never closed: " + events));
            assertThat(TimeUnit.NANOSECONDS.toMillis(firstClosed.nanoTime - firstOpened.nanoTime))
                    .as("the old connection serves until the stalled successor times out, and no longer")
                    .isBetween(MAX_CONNECTION_AGE_MILLIS + connectTimeoutMillis / 2,
                               MAX_CONNECTION_AGE_MILLIS + 2 * connectTimeoutMillis);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H2C", "H2" })
    @ParameterizedTest
    void connectionsAgingTogetherEachGetTheirOwnSuccessor(SessionProtocol protocol) throws Exception {
        final CountingConnectionPoolListener connections = new CountingConnectionPoolListener();
        try (ClientFactory factory = slowSuccessorFactory(connections, 3)) {
            final List<String> requestsThatWaitedForAConnection =
                    requestsAfterMaxAgeThatWaitedForAConnection(factory, protocol, 0, 0, 0);

            assertThat(requestsThatWaitedForAConnection)
                    .as("no request sent after the connections age waits for a connection")
                    .isEmpty();
            assertThat(connections.closed())
                    .as("connections must still rotate at the max age")
                    .isGreaterThanOrEqualTo(3);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H2C", "H2" })
    @ParameterizedTest
    void connectionAgingWhileARequestConnectsGetsItsOwnSuccessor(SessionProtocol protocol)
            throws Exception {
        final CountingConnectionPoolListener connections = new CountingConnectionPoolListener();
        try (ClientFactory factory = slowSuccessorFactory(connections, 1)) {
            final List<String> requestsThatWaitedForAConnection =
                    requestsAfterMaxAgeThatWaitedForAConnection(factory, protocol, 0,
                                                                STILL_CONNECTING_AT_MAX_AGE_MILLIS);

            assertThat(requestsThatWaitedForAConnection)
                    .as("no request sent after the first connection ages waits for a connection")
                    .isEmpty();
            assertThat(connections.closed())
                    .as("connections must still rotate at the max age")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H2C", "H2" })
    @ParameterizedTest
    void requestSentWhileTheSuccessorConnectsIsServedByTheSuccessor(SessionProtocol protocol)
            throws Exception {
        final ConnectionEvents connections = new ConnectionEvents();
        try (ClientFactory factory = slowSuccessorFactory(connections, 1)) {
            final WebClient client = WebClient.builder(oneStreamServer.uri(protocol))
                                              .factory(factory)
                                              .build();
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
            final CompletableFuture<AggregatedHttpResponse> busy = client.get("/slow").aggregate();

            final long successorConnectingNanos =
                    connections.events().get(0).nanoTime +
                    TimeUnit.MILLISECONDS.toNanos(MAX_CONNECTION_AGE_MILLIS + SLOW_CONNECT_MILLIS / 2);
            TimeUnit.NANOSECONDS.sleep(successorConnectingNanos - System.nanoTime());
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
                ctx = captor.get();
            }
            final ClientConnectionTimings timings = ctx.log().whenComplete().join().connectionTimings();

            assertThat(timings).isNotNull();
            assertThat(timings.pendingAcquisitionDurationNanos())
                    .as("a request sent while the busy connection's successor connects waits for it")
                    .isNotNegative();
            assertThat(timings.socketConnectDurationNanos())
                    .as("a request sent while the busy connection's successor connects is served by it " +
                        "rather than by a connection of its own")
                    .isNegative();
            assertThat(connections.events())
                    .filteredOn(e -> e.open)
                    .as("no connection opens besides the busy connection and its successor")
                    .hasSize(2);
            assertThat(busy.join().status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void webSocketConnectionIsNotReplaced() throws Exception {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE_MILLIS)
                                                  .replaceConnectionOnMaxAge(true)
                                                  .build()) {
            final WebSocketSession session =
                    WebSocketClient.builder(server.uri(SessionProtocol.H2C, SerializationFormat.WS))
                                   .factory(factory)
                                   .build()
                                   .connect("/ws")
                                   .join();
            Thread.sleep(MAX_CONNECTION_AGE_MILLIS + 500);

            final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                                              .factory(factory)
                                              .build();
            final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.POST, "/method");
            final CompletableFuture<AggregatedHttpResponse> res = client.execute(req).aggregate();
            req.write(HttpData.ofUtf8("hello"));
            req.close();
            assertThat(res.join().contentUtf8())
                    .as("a streaming request sent after the WebSocket connection ages is not a " +
                        "WebSocket CONNECT")
                    .isEqualTo("POST");

            session.outbound().close();
            session.inbound().abort();
        }
    }

    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H1" })
    @ParameterizedTest
    void http1ConnectionsAreStillClosedBeforeTheirReplacementOpens(SessionProtocol protocol) {
        final CountingConnectionPoolListener connections = new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(connections)
                                                  .idleTimeoutMillis(0)
                                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE_MILLIS)
                                                  .replaceConnectionOnMaxAge(true)
                                                  .tlsNoVerify()
                                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(protocol))
                                              .factory(factory)
                                              .build();
            for (int i = 0; i < 3; i++) {
                assertThat(connections.opened()).isEqualTo(i);
                assertThat(connections.closed()).isEqualTo(i);
                assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
                final int expected = i + 1;
                await().untilAsserted(() -> assertThat(connections.opened()).isEqualTo(expected));
                await().untilAsserted(() -> assertThat(connections.closed()).isEqualTo(expected));
            }
        }
    }

    private static List<Integer> indexesOfSteadyRequestsThatWaitedForAConnection(
            ClientFactory factory, SessionProtocol protocol) throws InterruptedException {
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .factory(factory)
                                          .build();
        final List<Integer> requestsThatWaitedForAConnection = new ArrayList<>();
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STEADY_TRAFFIC_MILLIS);
        for (int i = 0; System.nanoTime() < deadlineNanos; i++) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
                ctx = captor.get();
            }
            if (ctx.log().whenComplete().join().connectionTimings() != null) {
                requestsThatWaitedForAConnection.add(i);
            }
            Thread.sleep(20);
        }
        return requestsThatWaitedForAConnection;
    }

    private static ClientFactory slowSuccessorFactory(ConnectionPoolListener listener,
                                                      int numFastConnections) {
        final AtomicInteger numChannels = new AtomicInteger();
        return ClientFactory.builder()
                            .connectionPoolListener(listener)
                            .idleTimeoutMillis(0)
                            .maxConnectionAgeMillis(MAX_CONNECTION_AGE_MILLIS)
                            .replaceConnectionOnMaxAge(true)
                            .option(ClientFactoryOptions.CHANNEL_PIPELINE_CUSTOMIZER, pipeline -> {
                                if (numChannels.incrementAndGet() > numFastConnections) {
                                    pipeline.addFirst(new SlowConnectHandler());
                                }
                            })
                            .tlsNoVerify()
                            .build();
    }

    private static List<String> requestsAfterMaxAgeThatWaitedForAConnection(
            ClientFactory factory, SessionProtocol protocol, long... callerStartMillis) throws Exception {
        final WebClient client = WebClient.builder(oneStreamServer.uri(protocol))
                                          .factory(factory)
                                          .build();
        final List<String> requestsThatWaitedForAConnection = new CopyOnWriteArrayList<>();
        final long startNanos = System.nanoTime();
        final ExecutorService callers = Executors.newFixedThreadPool(callerStartMillis.length);
        try {
            final List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < callerStartMillis.length; i++) {
                final int caller = i;
                results.add(callers.submit(() -> {
                    Thread.sleep(callerStartMillis[caller]);
                    for (;;) {
                        final long sentAtMillis =
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                        if (sentAtMillis >= STEADY_TRAFFIC_MILLIS) {
                            return null;
                        }
                        final ClientRequestContext ctx;
                        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                            assertThat(client.get("/").aggregate().join().status())
                                    .isEqualTo(HttpStatus.OK);
                            ctx = captor.get();
                        }
                        if (sentAtMillis >= MAX_CONNECTION_AGE_MILLIS &&
                            ctx.log().whenComplete().join().connectionTimings() != null) {
                            requestsThatWaitedForAConnection.add(
                                    "caller " + caller + " at " + sentAtMillis + " ms");
                        }
                    }
                }));
            }
            for (Future<?> result : results) {
                result.get();
            }
        } finally {
            callers.shutdownNow();
        }
        return new ArrayList<>(requestsThatWaitedForAConnection);
    }

    private static Consumer<ChannelPipeline> onSecondChannel(Consumer<ChannelPipeline> customizer) {
        final AtomicInteger numChannels = new AtomicInteger();
        return pipeline -> {
            if (numChannels.incrementAndGet() == 2) {
                customizer.accept(pipeline);
            }
        };
    }

    private static final class CloseOnActiveHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.close();
        }
    }

    private static final class SlowConnectHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
                            SocketAddress localAddress, ChannelPromise promise) {
            ctx.executor().schedule(() -> ctx.connect(remoteAddress, localAddress, promise),
                                    SLOW_CONNECT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private static final class DropReadsHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
        }
    }

    private static final class ConnectionEvents implements ConnectionPoolListener {

        private final List<ConnectionEvent> events = new CopyOnWriteArrayList<>();

        List<ConnectionEvent> events() {
            return new ArrayList<>(events);
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
        final long nanoTime = System.nanoTime();

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
