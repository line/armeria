/*
 * Copyright 2023 LINE Corporation
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.Lists;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeMap;

public class InitiateConnectionShutdownTest {
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/ok", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.service("/delayed_ok", (ctx, req) -> {
                return HttpResponse.delayed(HttpResponse.of(HttpStatus.OK),
                                            Duration.ofMillis(500));
            });
        }
    };

    final List<RequestLogAccess> requestLogAccesses = Lists.newCopyOnWriteArrayList();
    final AtomicBoolean completed = new AtomicBoolean();
    final AtomicInteger connectionOpen = new AtomicInteger();
    final AtomicInteger connectionClosed = new AtomicInteger();
    final ConnectionPoolListener poolListener = new ConnectionPoolListener() {
        @Override
        public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                   InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
            connectionOpen.incrementAndGet();
        }

        @Override
        public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                     InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
            connectionClosed.incrementAndGet();
        }
    };

    @BeforeEach
    void resetVariables() {
        requestLogAccesses.clear();
        completed.set(false);
        connectionOpen.set(0);
        connectionClosed.set(0);
    }

    @ParameterizedTest
    @CsvSource({
            "H1C, /ok,         BEFORE_SENDING_REQ",
            "H1C, /ok,         AFTER_SENDING_REQ",
            "H1C, /delayed_ok, BEFORE_SENDING_REQ",
            "H1C, /delayed_ok, AFTER_SENDING_REQ",
            "H2C, /ok,         BEFORE_SENDING_REQ",
            "H2C, /ok,         AFTER_SENDING_REQ",
            "H2C, /delayed_ok, BEFORE_SENDING_REQ",
            "H2C, /delayed_ok, AFTER_SENDING_REQ"
    })
    void testConnectionShutdown(SessionProtocol protocol, String path, ConnectionShutdownTiming timing)
            throws Exception {
        try (ClientFactory clientFactory = getClientFactory()) {
            final WebClient client = newWebClient(protocol, clientFactory, timing);
            final AggregatedHttpResponse res = client.blocking().get(path);

            assertSingleConnection();
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(requestLogAccesses.size()).isEqualTo(1);

            final String connectionHeaderValue =
                    requestLogAccesses.get(0).ensureRequestComplete()
                                      .requestHeaders().get(HttpHeaderNames.CONNECTION);
            switch (timing) {
                case BEFORE_SENDING_REQ:
                    assertThat(connectionHeaderValue).isEqualTo("close");
                    break;
                case AFTER_SENDING_REQ:
                    assertThat(connectionHeaderValue).isNull();
                    break;
                default:
                    throw new IllegalArgumentException("unexpected shutdown timing: " + timing);
            }
        }

        assertSingleConnectionNow();
    }

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C" })
    void connectionShouldNotBeClosedIfThereArePendingRequests(SessionProtocol protocol) throws Exception {
        try (ClientFactory clientFactory = getClientFactory()) {
            final WebClient client = newWebClient(protocol, clientFactory, ConnectionShutdownTiming.NEVER);
            final CompletableFuture<AggregatedHttpResponse> firstResFuture;
            final CompletableFuture<AggregatedHttpResponse> secondResFuture;
            try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
                // Send the first request synchronously, but don't wait for the response yet.
                firstResFuture = client.get("/delayed_ok").aggregate();
                final ClientRequestContext firstCtx = ctxCaptor.get();
                firstCtx.log().whenRequestComplete().get();

                // Initiate the shutdown from the first context after the second request was sent through
                // the same connection.
                secondResFuture = client.get("/ok").aggregate();
                final ClientRequestContext secondCtx = ctxCaptor.getAll().get(1);
                secondCtx.log().whenRequestComplete().thenRun(() -> {
                    initiateConnectionShutdown(firstCtx);
                });
            }

            // Wait for both responses.
            final AggregatedHttpResponse firstRes = firstResFuture.get();
            final AggregatedHttpResponse secondRes = secondResFuture.get();

            assertSingleConnection();

            assertThat(firstRes.status()).isEqualTo(HttpStatus.OK);
            assertThat(secondRes.status()).isEqualTo(HttpStatus.OK);

            assertThat(requestLogAccesses.size()).isEqualTo(2);
            assertThat(requestLogAccesses.get(0).ensureRequestComplete().requestHeaders()
                                         .get("connection")).isNull();
            assertThat(requestLogAccesses.get(1).ensureRequestComplete().requestHeaders()
                                         .get("connection")).isNull();
        }

        assertSingleConnectionNow();
    }

    private ClientFactory getClientFactory() {
        return ClientFactory.builder().connectionPoolListener(poolListener)
                            .workerGroup(1).useHttp1Pipelining(true)
                            .build();
    }

    private WebClient newWebClient(SessionProtocol protocol,
                                   ClientFactory clientFactory,
                                   ConnectionShutdownTiming timing) {
        return WebClient.builder(server.uri(protocol)).factory(clientFactory).decorator(
                (delegate, ctx, req) -> {
                    requestLogAccesses.add(ctx.log());
                    switch (timing) {
                        case BEFORE_SENDING_REQ:
                            initiateConnectionShutdown(ctx);
                            break;
                        case AFTER_SENDING_REQ:
                            ctx.log().whenRequestComplete()
                               .thenRun(() -> initiateConnectionShutdown(ctx));
                            break;
                        case NEVER:
                            break;
                        default:
                            throw new Error();
                    }
                    return delegate.execute(ctx, req);
                }).build();
    }

    private void assertSingleConnection() throws InterruptedException {
        // Wait until the future returned by ctx.initiateConnectionShutdown() is complete.
        await().until(completed::get);

        // Make sure open one connection was open and then closed.
        await().untilAsserted(this::assertSingleConnectionNow);
    }

    private void assertSingleConnectionNow() {
        assertThat(connectionOpen.get())
                .withFailMessage(() -> connectionOpen.get() + " connections were open.")
                .isEqualTo(1);
        assertThat(connectionClosed.get())
                .withFailMessage(() -> connectionClosed.get() + " connections were closed.")
                .isEqualTo(1);
    }

    private void initiateConnectionShutdown(ClientRequestContext ctx) {
        ctx.initiateConnectionShutdown().thenRun(() -> completed.set(true));
    }

    private enum ConnectionShutdownTiming {
        BEFORE_SENDING_REQ,
        AFTER_SENDING_REQ,
        NEVER
    }
}
