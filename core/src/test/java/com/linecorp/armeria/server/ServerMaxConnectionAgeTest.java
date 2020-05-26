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

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.util.AttributeMap;

class ServerMaxConnectionAgeTest {

    private static final Logger logger = LoggerFactory.getLogger(ServerMaxConnectionAgeTest.class);

    private static final int MAX_CONNECTION_AGE_MILLIS = 5000;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.idleTimeoutMillis(0);
            sb.maxConnectionAgeMillis(MAX_CONNECTION_AGE_MILLIS);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @RegisterExtension
    static ServerExtension serverKeepAlive = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.idleTimeoutMillis(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    AtomicInteger opened;
    AtomicInteger closed;
    ConnectionPoolListener connectionPoolListener;

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

    @CsvSource({ "H1C, false", "H1C, true", "H2C, true" })
    @ParameterizedTest
    void shouldDisconnectWhenMaxConnectionAgeIsExceeded(SessionProtocol protocol, boolean useHttp1Pipelining)
            throws InterruptedException {
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .connectionPoolListener(connectionPoolListener)
                             .useHttp1Pipelining(useHttp1Pipelining)
                             .idleTimeoutMillis(0)
                             .build();
        final WebClient client =
                WebClient.builder(server.uri(protocol))
                         .factory(clientFactory)
                         .build();

        final Stopwatch stopwatch = Stopwatch.createStarted();
        final AtomicInteger oldClosed = new AtomicInteger();

        while (closed.get() < 5) {
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
            assertThat(opened).hasValue(closed.intValue() + 1);

            if (closed.get() > oldClosed.get()) {
                assertThat(stopwatch.elapsed().toMillis())
                        .isCloseTo(MAX_CONNECTION_AGE_MILLIS, withinPercentage(25));
                oldClosed.set(closed.get());
                stopwatch.reset().start();
            }
            Thread.sleep(100);
        }
    }

    @CsvSource({ "H1C", "H2C"})
    @ParameterizedTest
    void shouldNotDisconnect(SessionProtocol protocol) throws InterruptedException {
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .connectionPoolListener(connectionPoolListener)
                             .idleTimeoutMillis(0)
                             .build();
        final WebClient client =
                WebClient.builder(serverKeepAlive.uri(protocol))
                         .factory(clientFactory)
                         .build();

        for (int i = 0; i < 100; i++) {
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
            assertThat(opened).hasValue(1);
            assertThat(closed).hasValue(0);
            Thread.sleep(100);
        }
    }
}
