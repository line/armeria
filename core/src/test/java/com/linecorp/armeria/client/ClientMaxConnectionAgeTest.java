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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.util.AttributeMap;

class ClientMaxConnectionAgeTest {

    private static final long MAX_CONNECTION_AGE = 2000;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(OK));
            sb.annotatedService("/delayed", new Object() {

                @Get
                public HttpResponse delayed(@Param("seconds") long seconds) {
                    return HttpResponse.delayed(
                            HttpResponse.of(200), Duration.ofSeconds(seconds));
                }
            });
        }

        @Override
        protected boolean runForEachTest() {
            return true;
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

    @EnumSource(value = SessionProtocol.class, names = {"PROXY", "UNDEFINED"}, mode = Mode.EXCLUDE)
    @ParameterizedTest
    void maxConnectionAge(SessionProtocol protocol) {
        final int maxClosedConnection = 5;
        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .connectionPoolListener(connectionPoolListener)
                                                         .idleTimeoutMillis(0)
                                                         .maxConnectionAgeMillis(MAX_CONNECTION_AGE)
                                                         .meterRegistry(meterRegistry)
                                                         .tlsNoVerify()
                                                         .build();
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .factory(clientFactory)
                                          .responseTimeoutMillis(0)
                                          .build();

        for (int i = 0; i < 5; i++) {
            assertThat(opened).hasValue(i);
            assertThat(closed).hasValue(i);
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
            await().timeout(Duration.ofSeconds(2)).untilAtomic(opened, Matchers.is(i + 1));
            await().timeout(Duration.ofSeconds(5)).untilAtomic(closed, Matchers.is(i + 1));
        }

        await().untilAsserted(() -> {
            String scheme = protocol.uriText();
            if ("http".equals(scheme)) {
                scheme = "h2c";
            }
            if ("https".equals(scheme)) {
                scheme = "h2";
            }

            assertThat(MoreMeters.measureAll(meterRegistry))
                    .hasEntrySatisfying(
                            "armeria.client.connections.lifespan.percentile#value{phi=0,protocol=" +
                            scheme + '}',
                            value -> {
                                assertThat(value * 1000)
                                        .isBetween(MAX_CONNECTION_AGE - 300.0, MAX_CONNECTION_AGE + 4000.0);
                            })
                    .hasEntrySatisfying(
                            "armeria.client.connections.lifespan.percentile#value{phi=1,protocol=" +
                            scheme + '}',
                            value -> {
                                assertThat(value * 1000)
                                        .isBetween(MAX_CONNECTION_AGE - 300.0, MAX_CONNECTION_AGE + 4000.0);
                            })
                    .hasEntrySatisfying(
                            "armeria.client.connections.lifespan#count{protocol=" + scheme + '}',
                            value -> assertThat(value).isEqualTo(maxClosedConnection));
        });
        clientFactory.closeAsync();
    }

    @EnumSource(value = SessionProtocol.class, names = {"PROXY", "UNDEFINED"}, mode = Mode.EXCLUDE)
    @ParameterizedTest
    void shouldCloseIdleConnectionByMaxConnectionAge(SessionProtocol protocol) {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(connectionPoolListener)
                                                  .idleTimeoutMillis(0)
                                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE)
                                                  .tlsNoVerify()
                                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(protocol))
                                              .factory(factory)
                                              .responseTimeoutMillis(0)
                                              .build();

            assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
            await().untilAtomic(opened, Matchers.is(1));
            await().untilAtomic(closed, Matchers.is(1));
        }
    }

    @EnumSource(value = SessionProtocol.class, names = {"PROXY", "UNDEFINED"}, mode = Mode.EXCLUDE)
    @ParameterizedTest
    void shouldCloseConnectionAfterLongRequest(SessionProtocol protocol) throws Exception {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(connectionPoolListener)
                                                  .idleTimeoutMillis(0)
                                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE)
                                                  .tlsNoVerify()
                                                  .build()) {
            final WebClient client = WebClient.builder(server.uri(protocol))
                                              .factory(factory)
                                              .responseTimeoutMillis(0)
                                              .build();

            assertThat(client.get("/delayed?seconds=4").aggregate().join().status()).isEqualTo(OK);

            await().untilAtomic(opened, Matchers.is(1));
            await().untilAtomic(closed, Matchers.is(1));
        }
    }

    @EnumSource(value = SessionProtocol.class, names = {"PROXY", "UNDEFINED"}, mode = Mode.EXCLUDE)
    @ParameterizedTest
    void shouldCloseConnectionAfterLongRequestTimeout(SessionProtocol protocol) throws Exception {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(connectionPoolListener)
                                                  .idleTimeoutMillis(0)
                                                  .maxConnectionAgeMillis(MAX_CONNECTION_AGE)
                                                  .tlsNoVerify()
                                                  .build()) {
            final long responseTimeoutMillis = MAX_CONNECTION_AGE + 1000;
            final WebClient client = WebClient.builder(server.uri(protocol))
                                              .factory(factory)
                                              .responseTimeoutMillis(responseTimeoutMillis)
                                              .build();

            assertThatThrownBy(() -> client.get("/delayed?seconds=10").aggregate().join().status())
                    .hasRootCauseInstanceOf(ResponseTimeoutException.class);

            await().untilAtomic(opened, Matchers.is(1));
            await().untilAtomic(closed, Matchers.is(1));
        }
    }
}
