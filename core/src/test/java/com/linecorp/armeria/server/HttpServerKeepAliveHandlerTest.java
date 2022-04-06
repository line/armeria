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
import static org.assertj.core.api.Assertions.withinPercentage;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.internal.testing.FlakyTest;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.util.AttributeMap;

@FlakyTest
class HttpServerKeepAliveHandlerTest {

    private static final ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    private static final long serverIdleTimeout = 20000;
    private static final long serverPingInterval = 10000;

    @Mock
    private Appender<ILoggingEvent> appender;

    @Captor
    private ArgumentCaptor<ILoggingEvent> loggingEventCaptor;

    @BeforeEach
    void setupLogger() {
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void cleanupLogger() {
        rootLogger.detachAppender(appender);
    }

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
    static ServerExtension serverWithNoKeepAlive = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.idleTimeoutMillis(0);
            sb.pingIntervalMillis(0);
            sb.requestTimeoutMillis(1000);
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
            sb.service("/streaming", (ctx, req) -> HttpResponse.streaming());
        }
    };

    @RegisterExtension
    static ServerExtension serverWithNoIdleTimeout = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.idleTimeoutMillis(0);
            sb.pingIntervalMillis(serverPingInterval);
            sb.decorator(LoggingService.newDecorator());
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
            sb.service("/streaming", (ctx, req) -> HttpResponse.streaming());
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
    @CsvSource({ "H1C, false", "H2C, false", "H2C, true" })
    void closeByClientIdleTimeout(SessionProtocol protocol, boolean useHttp2Preface) {
        final long clientIdleTimeout = 2000;
        try (ClientFactory factory = newClientFactory(clientIdleTimeout, useHttp2Preface)) {
            final WebClient client = newWebClient(factory, server.uri(protocol));
            final MeterRegistry meterRegistry = client.options().factory().meterRegistry();

            final Stopwatch stopwatch = Stopwatch.createStarted();
            client.get("/").aggregate().join();
            assertThat(counter).hasValue(1);

            // The HTTP/2 PING frames sent by the server should not prevent to close an idle connection.
            await().untilAtomic(counter, Matchers.is(0));
            final long elapsed = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
            assertThat(elapsed).isBetween(clientIdleTimeout, serverIdleTimeout - 1000);
            assertThat(MoreMeters.measureAll(meterRegistry))
                    .hasEntrySatisfying(
                            "armeria.client.connections.lifespan#total{protocol=" + protocol.uriText() + '}',
                            value -> assertThat(value * 1000).isCloseTo(elapsed, withinPercentage(25)));
        }
    }

    @Test
    void http1CloseByServerIdleTimeout() {
        // longer than the idle timeout of the server.
        final long clientIdleTimeout = serverIdleTimeout + 5000;
        try (ClientFactory factory = newClientFactory(clientIdleTimeout, false)) {
            final WebClient client = newWebClient(factory, server.uri(SessionProtocol.H1C));

            final Stopwatch stopwatch = Stopwatch.createStarted();
            client.get("/").aggregate().join();
            assertThat(counter).hasValue(1);

            // The connection should be closed by server
            await().timeout(Duration.ofMillis(clientIdleTimeout + 5000)).untilAtomic(counter, Matchers.is(0));
            final long elapsed = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
            assertThat(elapsed).isBetween(serverIdleTimeout, clientIdleTimeout - 1000);
        }
    }

    @ParameterizedTest
    @CsvSource({ "H1C, false", "H2C, false", "H2C, true" })
    void shouldCloseConnectionWheNoActiveRequests(SessionProtocol protocol, boolean useHttp2Preface) {
        final long clientIdleTimeout = 10000;
        final long tolerance = 3000;
        try (ClientFactory factory = newClientFactory(clientIdleTimeout, 0, useHttp2Preface)) {
            final WebClient client = newWebClient(factory, 0, serverWithNoKeepAlive.uri(protocol));

            final Stopwatch stopwatch = Stopwatch.createStarted();
            client.get("/streaming").aggregate().join();
            assertThat(counter).hasValue(1);

            // After the request is closed by server side RequestTimeoutException,
            // if no requests is in progress, the connection should be closed by client idle timeout scheduler
            await().timeout(Duration.ofMillis(clientIdleTimeout + tolerance))
                   .untilAtomic(counter, Matchers.is(0));
            final long elapsed = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS);
            assertThat(elapsed).isBetween(clientIdleTimeout - tolerance, clientIdleTimeout * 2 + tolerance);
        }
    }

    @ParameterizedTest
    @CsvSource({ "false", "true" })
    void serverShouldSendPingWithNoIdleTimeout(boolean useHttp2Preface) {
        final long clientIdleTimeout = 0;
        final long clientPingInterval = 0;
        final long responseTimeout = 0;
        try (ClientFactory factory = newClientFactory(clientIdleTimeout, clientPingInterval, useHttp2Preface)) {
            final WebClient client = newWebClient(factory, responseTimeout,
                                                  serverWithNoIdleTimeout.uri(SessionProtocol.H2C));
            client.get("/").aggregate().join();
            assertThat(counter).hasValue(1);
            await().timeout(Duration.ofMinutes(1)).untilAsserted(this::assertPing);
        }
    }

    @CsvSource({ "H1C, false", "H2C, false", "H2C, true" })
    @ParameterizedTest
    void clientShouldSendPingWithNoIdleTimeout(SessionProtocol protocol, boolean useHttp2Preface) {
        final long clientIdleTimeout = 0;
        final long clientPingInterval = 10000;
        final long responseTimeout = 0;
        try (ClientFactory factory = newClientFactory(clientIdleTimeout, clientPingInterval, useHttp2Preface)) {
            final WebClient client = newWebClient(factory, responseTimeout,
                                                  serverWithNoKeepAlive.uri(protocol));
            client.get("/").aggregate().join();
            await().timeout(Duration.ofMinutes(1)).untilAsserted(this::assertPing);
        }
    }

    private ClientFactory newClientFactory(long clientIdleTimeout, boolean useHttp2Preface) {
        return newClientFactory(clientIdleTimeout, Flags.defaultPingIntervalMillis(), useHttp2Preface);
    }

    private ClientFactory newClientFactory(long clientIdleTimeout, long pingIntervalMillis,
                                           boolean useHttp2Preface) {
        return ClientFactory.builder()
                            .meterRegistry(new SimpleMeterRegistry())
                            .idleTimeoutMillis(clientIdleTimeout)
                            .pingIntervalMillis(pingIntervalMillis)
                            .connectionPoolListener(listener)
                            .useHttp2Preface(useHttp2Preface)
                            .build();
    }

    private static WebClient newWebClient(ClientFactory factory, URI uri) {
        return newWebClient(factory, Flags.defaultResponseTimeoutMillis(), uri);
    }

    private static WebClient newWebClient(ClientFactory factory, long responseTimeout, URI uri) {
        return WebClient.builder(uri)
                        .factory(factory)
                        .responseTimeoutMillis(responseTimeout)
                        .build();
    }

    private void assertPing() {
        verify(appender, atLeastOnce()).doAppend(loggingEventCaptor.capture());
        assertThat(loggingEventCaptor.getAllValues()).anyMatch(event -> {
            return event.getLevel() == Level.DEBUG && event.getMessage().contains("PING write successful");
        });
    }
}
