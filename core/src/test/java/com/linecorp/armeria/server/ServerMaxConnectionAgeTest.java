/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.util.AttributeMap;

class ServerMaxConnectionAgeTest {

    private static final long MAX_CONNECTION_AGE = 1000;
    private static MeterRegistry meterRegistry;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);
            sb.connectionDrainDuration(Duration.ofMillis(10));
            sb.maxConnectionAgeMillis(MAX_CONNECTION_AGE);
            meterRegistry = new SimpleMeterRegistry();
            sb.meterRegistry(meterRegistry);
            sb.service("/", (ctx, req) -> HttpResponse.of(OK));
            sb.service("/slow", (ctx, req) -> {
                final HttpResponseWriter response = HttpResponse.streaming();
                final String body = "Disconnect";
                ctx.eventLoop().schedule(() -> {
                    // Write a response headers after max connection age expires.
                    response.write(ResponseHeaders.builder(200)
                                                  .contentLength(body.length())
                                                  .build());

                    ctx.eventLoop().schedule(() -> {
                        // Defer writing the response so that the session can receive new requests.
                        // This ensures that the session is disconnected after completing all pending requests.
                        response.write(HttpData.ofUtf8(body));
                        response.close();
                    }, 1000, TimeUnit.MILLISECONDS);
                }, MAX_CONNECTION_AGE + 200, TimeUnit.MILLISECONDS);
                return response;
            });
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @RegisterExtension
    static ServerExtension serverKeepAlive = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);
            sb.connectionDrainDuration(Duration.ofMillis(10));
            sb.service("/", (ctx, req) ->
                    HttpResponse.delayed(HttpResponse.of(OK), Duration.ofMillis(100)));
        }
    };

    private AtomicInteger opened;
    private AtomicInteger closed;
    private ConnectionPoolListener connectionPoolListener;

    @BeforeEach
    void setUp() {
        opened = new AtomicInteger();
        closed = new AtomicInteger();
        connectionPoolListener = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                opened.incrementAndGet();
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                closed.incrementAndGet();
            }
        };
    }

    @CsvSource({ "H1C", "H1" })
    @ParameterizedTest
    void http1MaxConnectionAge(SessionProtocol protocol) {
        final int maxClosedConnection = 5;
        final ConnectionPoolListener connectionPoolListener = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                opened.incrementAndGet();
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                closed.incrementAndGet();
            }
        };

        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .connectionPoolListener(connectionPoolListener)
                                                         .idleTimeoutMillis(0)
                                                         .tlsNoVerify()
                                                         .build();
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .factory(clientFactory)
                                          .responseTimeoutMillis(0)
                                          .build();

        while (closed.get() < maxClosedConnection) {
            final HttpResponse response = client.get("/");
            assertThat(response.aggregate().join().status()).isEqualTo(OK);
            response.whenComplete().join();
            final int closed = this.closed.get();
            assertThat(opened).hasValueBetween(closed, closed + 1);
        }

        await().untilAsserted(() -> {
            assertThat(MoreMeters.measureAll(meterRegistry))
                    .hasEntrySatisfying(
                            "armeria.server.connections.lifespan.percentile#value{phi=0,protocol=" +
                            protocol.uriText() + '}',
                            value -> {
                                assertThat(value * 1000)
                                        .isBetween(MAX_CONNECTION_AGE - 200.0, MAX_CONNECTION_AGE + 3000.0);
                            })
                    .hasEntrySatisfying(
                            "armeria.server.connections.lifespan.percentile#value{phi=1,protocol=" +
                            protocol.uriText() + '}',
                            value -> {
                                assertThat(value * 1000)
                                        .isBetween(MAX_CONNECTION_AGE - 200.0, MAX_CONNECTION_AGE + 3000.0);
                            })
                    .hasEntrySatisfying(
                            "armeria.server.connections.lifespan#count{protocol=" + protocol.uriText() + '}',
                            value -> assertThat(value).isEqualTo(maxClosedConnection));
        });
        clientFactory.closeAsync();
    }

    @Test
    void http1WithPipeliningMaxConnectionAge() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.httpPort())) {
            final PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.print("POST /slow HTTP/1.1\r\n");
            writer.print("Content-Length: 0\r\n");
            writer.print("\r\n");
            writer.flush();

            boolean closedConnection = false;
            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Read response headers
            while (true) {
                final String line = in.readLine();
                if (line.isEmpty()) {
                    break;
                }
                // Make sure that the max connection age is exceeded
                if ("connection: close".equals(line)) {
                    closedConnection = true;
                }
            }
            assertThat(closedConnection).isTrue();

            // Send a second request before fully receiving the previous request.
            closedConnection = false;
            writer.print("GET /slow HTTP/1.1\r\n\r\n");
            writer.flush();

            assertThat(in.readLine()).isEqualTo("DisconnectHTTP/1.1 200 OK");
            while (true) {
                final String line = in.readLine();
                if (line.isEmpty()) {
                    break;
                }
                if ("connection: close".equals(line)) {
                    closedConnection = true;
                }
            }

            assertThat(closedConnection).isTrue();
            assertThat(in.readLine()).isEqualTo("Disconnect");

            // Make sure that the connection is closed after the second request is handled.
            assertThat(in.readLine()).isNull();
        }
    }

    @CsvSource({ "H2C", "H2" })
    @ParameterizedTest
    void http2MaxConnectionAge(SessionProtocol protocol) throws InterruptedException {
        try (ClientFactory factory = newClientFactory(false)) {
            final WebClient client = newWebClient(factory, server.uri(protocol));
            // Make sure that a connection is opened.
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
            assertThat(opened).hasValue(1);
            assertThat(closed).hasValue(0);
            await().untilAsserted(() -> {
                // Schedule another request to avoid idling the connection.
                assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
                // Eventually connection should be closed due to max-age, a new connection will be opened.
                assertThat(opened)
                        .as("should have opened 2 connections. opened: %d", opened.get())
                        .hasValue(2);
                assertThat(closed)
                        .as("should have closed 1 connection. closed: %d", closed.get())
                        .hasValue(1);
            });
        }
    }

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void shouldNotDisconnect(SessionProtocol protocol) throws InterruptedException {
        try (ClientFactory factory = newClientFactory(false)) {
            final WebClient client = newWebClient(factory, serverKeepAlive.uri(protocol));

            for (int i = 0; i < 10; i++) {
                assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
                assertThat(opened).hasValue(1);
                assertThat(closed).hasValue(0);
                Thread.sleep(100);
            }
        }
    }

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void shouldCloseIdleConnectionByMaxConnectionAge(SessionProtocol protocol) {
        try (ClientFactory factory = newClientFactory(false)) {
            final WebClient client = newWebClient(factory, server.uri(protocol));

            assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
            await().untilAtomic(opened, Matchers.is(1));
            await().untilAtomic(closed, Matchers.is(1));
        }
    }

    private ClientFactory newClientFactory(boolean useHttp1PipeLine) {
        return ClientFactory.builder()
                            .connectionPoolListener(connectionPoolListener)
                            .useHttp1Pipelining(useHttp1PipeLine)
                            .idleTimeoutMillis(0)
                            .tlsNoVerify()
                            .build();
    }

    private static WebClient newWebClient(ClientFactory clientFactory, URI uri) {
        return WebClient.builder(uri)
                        .factory(clientFactory)
                        .responseTimeoutMillis(0)
                        .build();
    }
}
