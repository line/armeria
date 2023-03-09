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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

import io.netty.handler.codec.http.HttpHeaderValues;

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
    final List<Throwable> failedCompletions = Lists.newCopyOnWriteArrayList();

    @BeforeEach
    void resetVariables() {
        requestLogAccesses.clear();
        completed.set(false);
        failedCompletions.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "H1C, /ok",
            "H1C, /delayed_ok",
            "H2C, /ok",
            "H2C, /delayed_ok",
    })
    void testConnectionIsNotAcquired(SessionProtocol protocol, String path) throws Exception {
        final AtomicReference<Throwable> resCauseHolder = new AtomicReference<>();
        final CountingConnectionPoolListener countingListener = new CountingConnectionPoolListener();
        try (ClientFactory clientFactory = getClientFactory(countingListener)) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(clientFactory).decorator(
                    (delegate, ctx, req) -> {
                        initiateConnectionShutdown(ctx);
                        throw new RuntimeException();
                    }).build();

            try {
                client.blocking().get(path);
            } catch (Throwable ex) {
                resCauseHolder.set(ex);
            }

            assertNoConnectionNow(countingListener);

            assertThat(resCauseHolder.get()).isOfAnyClassIn(RuntimeException.class);

            assertThat(failedCompletions).hasSize(1);
            assertThat(failedCompletions.get(0)).isOfAnyClassIn(IllegalStateException.class);
        }
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
            "H2C, /delayed_ok, AFTER_SENDING_REQ",
    })
    void testConnectionShutdown(SessionProtocol protocol, String path, ConnectionShutdownTiming timing)
            throws Exception {
        final CountingConnectionPoolListener countingListener = new CountingConnectionPoolListener();
        try (ClientFactory clientFactory = getClientFactory(countingListener)) {
            final WebClient client = newWebClient(protocol, clientFactory, timing);
            final AggregatedHttpResponse res = client.blocking().get(path);

            assertSingleConnection(countingListener);
            assertThat(failedCompletions).isEmpty();
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(requestLogAccesses.size()).isEqualTo(1);
            assertConnectionHeader(requestLogAccesses.get(0), timing);
        }

        assertSingleConnectionNow(countingListener);
    }

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C" })
    void connectionShouldNotBeClosedIfThereArePendingRequests(SessionProtocol protocol) throws Exception {
        final CountingConnectionPoolListener countingListener = new CountingConnectionPoolListener();
        try (ClientFactory clientFactory = getClientFactory(countingListener)) {
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

            assertSingleConnection(countingListener);

            assertThat(failedCompletions).isEmpty();

            assertThat(firstRes.status()).isEqualTo(HttpStatus.OK);
            assertThat(secondRes.status()).isEqualTo(HttpStatus.OK);

            assertThat(requestLogAccesses.size()).isEqualTo(2);
            assertHasNoConnectionHeader(requestLogAccesses.get(0));
            assertHasNoConnectionHeader(requestLogAccesses.get(1));
        }

        assertSingleConnectionNow(countingListener);
    }

    private static ClientFactory getClientFactory(CountingConnectionPoolListener countingListener) {
        return ClientFactory.builder().connectionPoolListener(countingListener)
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

    private void assertSingleConnection(CountingConnectionPoolListener countingListener)
            throws InterruptedException {
        // Wait until the future returned by ctx.initiateConnectionShutdown() is complete.
        await().until(completed::get);

        // Make sure open one connection was open and then closed.
        await().untilAsserted(() -> assertSingleConnectionNow(countingListener));
    }

    private static void assertSingleConnectionNow(CountingConnectionPoolListener countingListener) {
        assertThat(countingListener.opened())
                .withFailMessage(() -> countingListener.opened() + " connections were open.")
                .isEqualTo(1);
        assertThat(countingListener.closed())
                .withFailMessage(() -> countingListener.closed() + " connections were closed.")
                .isEqualTo(1);
    }

    private static void assertNoConnectionNow(CountingConnectionPoolListener countingListener) {
        assertThat(countingListener.opened())
                .withFailMessage(() -> countingListener.opened() + " connections were open.")
                .isEqualTo(0);
        assertThat(countingListener.closed())
                .withFailMessage(() -> countingListener.closed() + " connections were closed.")
                .isEqualTo(0);
    }

    private void initiateConnectionShutdown(ClientRequestContext ctx) {
        ctx.initiateConnectionShutdown().handle((ignore, ex) -> {
            completed.set(true);
            if (ex != null) {
                failedCompletions.add(ex);
            }
            return null;
        });
    }

    private static void assertConnectionHeader(RequestLogAccess logAccess, ConnectionShutdownTiming timing) {
        switch (timing) {
            case BEFORE_SENDING_REQ:
                assertHasConnectionCloseHeader(logAccess);
                break;
            case AFTER_SENDING_REQ:
            case NEVER:
                assertHasNoConnectionHeader(logAccess);
                break;
            default:
                throw new IllegalArgumentException("unexpected shutdown timing: " + timing);
        }
    }

    private static void assertHasConnectionCloseHeader(RequestLogAccess logAccess) {
        assertThat(logAccess.ensureRequestComplete().requestHeaders().get(HttpHeaderNames.CONNECTION))
                .isEqualTo(HttpHeaderValues.CLOSE.toString());
    }

    private static void assertHasNoConnectionHeader(RequestLogAccess logAccess) {
        assertThat(logAccess.ensureRequestComplete().requestHeaders().get(HttpHeaderNames.CONNECTION)).isNull();
    }

    private enum ConnectionShutdownTiming {
        BEFORE_SENDING_REQ,
        AFTER_SENDING_REQ,
        NEVER
    }
}
