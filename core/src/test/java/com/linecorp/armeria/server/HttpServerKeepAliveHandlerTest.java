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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
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

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.util.AttributeMap;

class HttpServerKeepAliveHandlerTest {

    private static final long serverIdleTimeout = 20000;
    private static final long serverPingInterval = 10000;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.idleTimeoutMillis(serverIdleTimeout);
            sb.pingIntervalMillis(serverPingInterval);
            sb.decorator(LoggingService.newDecorator())
              .service("/", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    @RegisterExtension
    static ServerExtension serverWithNoIdleTimeout = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.idleTimeoutMillis(0);
            sb.pingIntervalMillis(0);
            sb.decorator(LoggingService.newDecorator())
              .service("/streaming", (ctx, req) -> HttpResponse.streaming());
        }
    };

    private AtomicInteger counter;
    private ConnectionPoolListener listener;

    @BeforeEach
    void setUp() {
        counter = new AtomicInteger();
        listener = new ConnectionPoolListener() {
            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                counter.incrementAndGet();
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
                counter.decrementAndGet();
            }
        };
    }

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C" })
    void closeByClientIdleTimeout(SessionProtocol protocol) throws InterruptedException {
        final long clientIdleTimeout = 2000;
        final WebClient client = newWebClient(clientIdleTimeout, server.uri(protocol));

        final Stopwatch stopwatch = Stopwatch.createStarted();
        client.get("/").aggregate().join();
        assertThat(counter).hasValue(1);

        // The HTTP/2 PING frames sent by the server should not prevent to close an idle connection.
        await().untilAtomic(counter, Matchers.is(0));
        final long elapsed = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
        assertThat(elapsed).isBetween(clientIdleTimeout, serverIdleTimeout - 1000);
    }

    @Test
    void http1CloseByServerIdleTimeout() throws InterruptedException {
        // longer than the idle timeout of the server.
        final long clientIdleTimeout = serverIdleTimeout + 5000;
        final WebClient client = newWebClient(clientIdleTimeout, server.uri(SessionProtocol.H1C));

        final Stopwatch stopwatch = Stopwatch.createStarted();
        client.get("/").aggregate().join();
        assertThat(counter).hasValue(1);

        // The connection should be closed by server
        await().timeout(Duration.ofMillis(clientIdleTimeout + 5000)).untilAtomic(counter, Matchers.is(0));
        final long elapsed = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
        assertThat(elapsed).isBetween(serverIdleTimeout, clientIdleTimeout - 1000);
    }

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C" })
    void shouldCloseConnectionWheNoActiveRequests(SessionProtocol protocol) throws InterruptedException {
        final long clientIdleTimeout = 2000;
        final WebClient client = newWebClient(clientIdleTimeout, serverWithNoIdleTimeout.uri(protocol));

        final Stopwatch stopwatch = Stopwatch.createStarted();
        client.get("/streaming").aggregate().join();
        assertThat(counter).hasValue(1);

        // After the request is closed by RequestTimeoutException,
        // if no requests is in progress, the connection should be closed by idle timeout scheduler
        await().untilAtomic(counter, Matchers.is(0));
        final long elapsed = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
        assertThat(elapsed).isBetween(clientIdleTimeout, serverIdleTimeout - 1000);
    }

    private WebClient newWebClient(long clientIdleTimeout, URI uri) {
        final ClientFactory factory = ClientFactory.builder()
                                                   .idleTimeoutMillis(clientIdleTimeout)
                                                   .connectionPoolListener(listener)
                                                   .build();
        return WebClient.builder(uri)
                        .factory(factory)
                        .build();
    }
}
