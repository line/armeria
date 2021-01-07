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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeMap;

class KeepAliveMaxNumRequestsTest {

    private static final int MAX_NUM_REQUESTS = 20;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);
            sb.maxNumRequests(MAX_NUM_REQUESTS);
            sb.service("/", (ctx, req) -> HttpResponse.of(OK));
        }
    };

    @RegisterExtension
    static ServerExtension serverWithNoKeepAlive = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(0);
            sb.decorator(LoggingService.newDecorator());
            sb.service("/", (ctx, req) -> HttpResponse.of(OK));
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
            private final Logger logger = LoggerFactory.getLogger(KeepAliveMaxNumRequestsTest.class);

            @Override
            public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                       InetSocketAddress localAddr, AttributeMap attrs) {
                opened.incrementAndGet();
                logger.info("opened: {}", opened);
            }

            @Override
            public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                         InetSocketAddress localAddr, AttributeMap attrs) {
                closed.incrementAndGet();
                logger.info("closed : {}", closed);
            }
        };
    }

    @ArgumentsSource(ProtocolProvider.class)
    @ParameterizedTest
    void shouldCloseConnectionAfterMaxNumRequests(SessionProtocol protocol, boolean serverKeepAlive) {
        final ClientFactoryBuilder clientFactoryBuilder =
                ClientFactory.builder()
                             .tlsNoVerify()
                             .connectionPoolListener(connectionPoolListener)
                             .idleTimeoutMillis(0);

        final WebClient client;
        if (serverKeepAlive) {
            client = WebClient.builder(server.uri(protocol))
                              .factory(clientFactoryBuilder.build())
                              .responseTimeoutMillis(0)
                              .build();
        } else {
            clientFactoryBuilder.maxNumRequests(MAX_NUM_REQUESTS);
            client = WebClient.builder(serverWithNoKeepAlive.uri(protocol))
                              .factory(clientFactoryBuilder.build())
                              .decorator(LoggingClient.newDecorator())
                              .responseTimeoutMillis(0)
                              .build();
        }

        for (int i = 1; i <= MAX_NUM_REQUESTS; i++) {
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
            await().untilAtomic(opened, Matchers.is(1));
            if (i < MAX_NUM_REQUESTS) {
                await().untilAtomic(closed, Matchers.is(0));
            } else {
                await().untilAtomic(closed, Matchers.is(1));
            }
        }

        for (int i = 1; i <= MAX_NUM_REQUESTS; i++) {
            assertThat(client.get("/").aggregate().join().status()).isEqualTo(OK);
            await().untilAtomic(opened, Matchers.is(2));
            if (i < MAX_NUM_REQUESTS) {
                await().untilAtomic(closed, Matchers.is(1));
            } else {
                await().untilAtomic(closed, Matchers.is(2));
            }
        }
    }

    private static final class ProtocolProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Arrays.stream(SessionProtocol.values())
                         .filter(protocol -> protocol != SessionProtocol.PROXY)
                         .flatMap(protocol -> Stream.of(Arguments.of(protocol, false),
                                                        Arguments.of(protocol, true)));
        }
    }
}
