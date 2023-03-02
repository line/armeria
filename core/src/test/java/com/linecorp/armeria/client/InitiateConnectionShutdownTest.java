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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.Lists;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
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
            final byte[] plaintext = "Hello, World!".getBytes(StandardCharsets.UTF_8);
            sb.service("/plaintext",
                       (ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, plaintext));
            sb.service("/delayed_streaming", (ctx, req) -> {
                final int chunkCount = 6;
                final int delayMillis = 10;
                final HttpResponseWriter writer = HttpResponse.streaming();
                ctx.eventLoop().schedule(() -> writer.write(ResponseHeaders.of(200)), delayMillis,
                                         TimeUnit.MILLISECONDS);
                for (int i = 0; i < chunkCount; i++) {
                    if (i == chunkCount - 1) {
                        ctx.eventLoop().schedule(() -> writer.write(HttpData.wrap(plaintext)),
                                                 delayMillis * (i + 2), TimeUnit.MILLISECONDS);
                        ctx.eventLoop().schedule(() -> writer.close(), delayMillis * (i + 3),
                                                 TimeUnit.MILLISECONDS);
                    } else {
                        ctx.eventLoop().schedule(() -> writer.write(HttpData.wrap(plaintext)),
                                                 delayMillis * (i + 2), TimeUnit.MILLISECONDS);
                    }
                }
                return writer;
            });
        }
    };

    final AtomicBoolean connectionClosed = new AtomicBoolean();
    final ConnectionPoolListener poolListener = new ConnectionPoolListener() {
        @Override
        public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                   InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
        }

        @Override
        public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                     InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
            connectionClosed.set(true);
        }
    };

    @BeforeEach
    void resetVariables() {
        connectionClosed.set(false);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testBeforeRequestIsConnected(SessionProtocol protocol) throws Exception {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<RequestLogAccess> requestLogAtomicReference = new AtomicReference<>();

        try (final ClientFactory clientFactory = getClientFactory()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(clientFactory).decorator(
                    (delegate, ctx, req) -> {
                        requestLogAtomicReference.set(ctx.log());
                        ctx.initiateConnectionShutdown().whenComplete((ignore, ex) -> completed.set(true));
                        return delegate.execute(ctx, req);
                    }).build();

            final CompletableFuture<AggregatedHttpResponse> res = client.get("/plaintext").aggregate();
            await().until(connectionClosed::get);
            await().until(completed::get);
            assertThat(res.get().contentUtf8()).isEqualTo("Hello, World!");
            assertThat(requestLogAtomicReference.get().ensureRequestComplete().requestHeaders()
                                                .get("connection")).isEqualTo("close");
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testBeforeRequestIsConnectedWithDelayedStreaming(SessionProtocol protocol)
            throws Exception {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<RequestLogAccess> requestLogAtomicReference = new AtomicReference<>();

        try (final ClientFactory clientFactory = getClientFactory()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(clientFactory).decorator(
                    (delegate, ctx, req) -> {
                        requestLogAtomicReference.set(ctx.log());
                        ctx.initiateConnectionShutdown().whenComplete((ignore, ex) -> completed.set(true));
                        return delegate.execute(ctx, req);
                    }).build();

            final CompletableFuture<AggregatedHttpResponse> res = client.get("/delayed_streaming").aggregate();
            await().until(connectionClosed::get);
            await().until(completed::get);
            assertThat(res.get().contentUtf8()).isEqualTo(
                    "Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!");
            assertThat(requestLogAtomicReference.get().ensureRequestComplete().requestHeaders()
                                                .get("connection")).isEqualTo("close");
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testAfterRequestIsConnected(SessionProtocol protocol) throws Exception {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<RequestLogAccess> requestLogAtomicReference = new AtomicReference<>();

        try (final ClientFactory clientFactory = getClientFactory()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(clientFactory).decorator(
                    (delegate, ctx, req) -> {
                        requestLogAtomicReference.set(ctx.log());
                        final HttpResponse res = delegate.execute(ctx, req);
                        ctx.log().whenRequestComplete().thenAccept(
                                it -> ctx.initiateConnectionShutdown()
                                         .whenComplete((ignore, ex) -> completed.set(true)));
                        return res;
                    }).build();

            final CompletableFuture<AggregatedHttpResponse> res = client.get("/plaintext").aggregate();
            await().until(connectionClosed::get);
            await().until(completed::get);
            assertThat(res.get().contentUtf8()).isEqualTo("Hello, World!");
            assertThat(requestLogAtomicReference.get().ensureRequestComplete().requestHeaders()
                                                .get("connection")).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testAfterRequestIsConnectedWithDelayedStreaming(SessionProtocol protocol) throws Exception {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<RequestLogAccess> requestLogAtomicReference = new AtomicReference<>();

        try (final ClientFactory clientFactory = getClientFactory()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(clientFactory).decorator(
                    (delegate, ctx, req) -> {
                        requestLogAtomicReference.set(ctx.log());
                        final HttpResponse res = delegate.execute(ctx, req);
                        ctx.log().whenRequestComplete().thenAccept(
                                it -> ctx.initiateConnectionShutdown()
                                         .whenComplete((ignore, ex) -> completed.set(true)));
                        return res;
                    }).build();

            final CompletableFuture<AggregatedHttpResponse> res = client.get("/delayed_streaming").aggregate();
            await().until(connectionClosed::get);
            await().until(completed::get);
            assertThat(res.get().contentUtf8()).isEqualTo(
                    "Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!");
            assertThat(requestLogAtomicReference.get().ensureRequestComplete().requestHeaders()
                                                .get("connection")).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testAfterRequestIsConnectedInSameConnection(SessionProtocol protocol) throws Exception {
        final List<Boolean> completeds = Lists.newCopyOnWriteArrayList();
        final List<RequestLogAccess> requestLogAccesses = Lists.newCopyOnWriteArrayList();

        try (final ClientFactory clientFactory = getClientFactory()) {
            final WebClient client = WebClient.builder(server.uri(protocol)).factory(clientFactory).decorator(
                    (delegate, ctx, req) -> {
                        requestLogAccesses.add(ctx.log());
                        final HttpResponse res = delegate.execute(ctx, req);
                        ctx.log().whenRequestComplete().thenAccept(
                                it -> ctx.initiateConnectionShutdown()
                                         .whenComplete((ignore, ex) -> completeds.add(true)));
                        return res;
                    }).build();

            final CompletableFuture<AggregatedHttpResponse> res1 = client.get("/plaintext").aggregate();
            final CompletableFuture<AggregatedHttpResponse> res2 = client.get("/delayed_streaming").aggregate();
            await().until(connectionClosed::get);
            await().until(() -> completeds.size() == 2);
            assertThat(completeds.get(0)).isTrue();
            assertThat(completeds.get(1)).isTrue();
            assertThat(res1.get().contentUtf8()).isEqualTo("Hello, World!");
            assertThat(res2.get().contentUtf8()).isEqualTo(
                    "Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!");
            assertThat(requestLogAccesses.size()).isEqualTo(2);
            assertThat(requestLogAccesses.get(0).ensureRequestComplete().requestHeaders()
                                         .get("connection")).isNull();
            assertThat(requestLogAccesses.get(1).ensureRequestComplete().requestHeaders()
                                         .get("connection")).isNull();
        }
    }

    private ClientFactory getClientFactory() {
        return ClientFactory.builder().connectionPoolListener(poolListener)
                            .workerGroup(1).useHttp1Pipelining(true)
                            .build();
    }
}
