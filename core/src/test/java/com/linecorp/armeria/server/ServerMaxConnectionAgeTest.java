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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.GoAwayReceivedException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.util.AttributeMap;

class ServerMaxConnectionAgeTest {

    private static final long MAX_CONNECTION_AGE = 1000;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);
            sb.maxConnectionAgeMillis(MAX_CONNECTION_AGE);
            sb.service("/", (ctx, req) -> HttpResponse.of(OK));
            sb.service("/slow", (ctx, req) ->
                    HttpResponse.delayed(HttpResponse.of("Disconnect"),
                                         Duration.ofMillis(MAX_CONNECTION_AGE + 100)));
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

    @Test
    void http1MaxConnectionAge() throws InterruptedException {
        final AtomicInteger oldClosed = new AtomicInteger();
        final WebClient client = newWebClient(server.uri(SessionProtocol.H1C));

        final Stopwatch stopwatch = Stopwatch.createStarted();
        while (closed.get() < 5) {
            final AggregatedHttpResponse agg = client.get("/").aggregate().join();
            assertThat(agg.status()).isEqualTo(OK);

            final int closed = this.closed.get();
            assertThat(opened).hasValueBetween(closed, closed + 1);

            // When a connection is disconnected, check the time from the previous disconnection.
            if (this.closed.get() > oldClosed.get()) {
                assertThat(stopwatch.elapsed().toMillis())
                        .isCloseTo(MAX_CONNECTION_AGE, withinPercentage(35));
                oldClosed.set(this.closed.get());
                stopwatch.reset().start();
            }
        }
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
            writer.print("GET / HTTP/1.1\r\n\r\n");
            writer.flush();

            assertThat(in.readLine()).isEqualTo("Disconnect");

            // The second request is closed before receiving any response.
            assertThat(in.readLine()).isNull();
        }
    }

    @Test
    void http2MaxConnectionAge() throws InterruptedException {
        final int concurrency = 200;
        final WebClient client = newWebClient(server.uri(SessionProtocol.H2C));

        // Make sure that a connection is opened.
        assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
        assertThat(opened).hasValue(1);

        final Supplier<HttpResponse> execute = () -> client.get("/");

        await().untilAsserted(() -> {
            final List<HttpResponse> responses = IntStream.range(0, concurrency)
                                                          .mapToObj(unused -> execute.get())
                                                          .collect(toImmutableList());
            Throwable cause = null;
            for (HttpResponse response : responses) {
                try {
                    response.aggregate().join();
                } catch (Exception e) {
                    cause = e;
                    break;
                }
            }
            assertThat(cause)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(UnprocessedRequestException.class)
                    .hasRootCauseInstanceOf(GoAwayReceivedException.class);
        });
    }

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void shouldNotDisconnect(SessionProtocol protocol) throws InterruptedException {
        final WebClient client = newWebClient(serverKeepAlive.uri(protocol));

        for (int i = 0; i < 10; i++) {
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
            assertThat(opened).hasValue(1);
            assertThat(closed).hasValue(0);
            Thread.sleep(100);
        }
    }

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void shouldCloseIdleConnectionByMaxConnectionAge(SessionProtocol protocol) {
        final WebClient client = newWebClient(server.uri(protocol));

        assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
        await().untilAtomic(opened, Matchers.is(1));
        await().untilAtomic(closed, Matchers.is(1));
    }

    private WebClient newWebClient(URI uri) {
        return newWebClient(uri, false);
    }

    private WebClient newWebClient(URI uri, boolean useHttp1PipeLine) {
        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .connectionPoolListener(connectionPoolListener)
                                                         .useHttp1Pipelining(useHttp1PipeLine)
                                                         .idleTimeoutMillis(0)
                                                         .build();
        return WebClient.builder(uri)
                        .factory(clientFactory)
                        .responseTimeoutMillis(0)
                        .build();
    }
}
