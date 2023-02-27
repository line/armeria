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

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.util.Sets;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

public class ClientRequestContextInitiateConnectionShutdownTest {
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

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testDisconnectConnectionBeforeConnected(SessionProtocol protocol) throws Exception {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<RequestLogAccess> requestLogAtomicReference = new AtomicReference<>();

        final WebClient client = WebClient.builder(server.uri(protocol)).decorator(
                (delegate, ctx, req) -> {
                    requestLogAtomicReference.set(ctx.log());
                    ctx.initiateConnectionShutdown().whenComplete((ignore, ex) -> completed.set(true));
                    return delegate.execute(ctx, req);
                }).build();

        final CompletableFuture<AggregatedHttpResponse> res = client.get("/plaintext").aggregate();
        await().until(completed::get);
        assertThat(requestLogAtomicReference.get().ensureRequestComplete().requestHeaders()
                                            .get("connection")).isEqualTo("close");
        assertThat(res.get().contentUtf8()).isEqualTo("Hello, World!");
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testDisconnectConnectionBeforeConnectedWithDelayedStreaming(SessionProtocol protocol)
            throws Exception {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<RequestLogAccess> requestLogAtomicReference = new AtomicReference<>();

        final WebClient client = WebClient.builder(server.uri(protocol)).decorator(
                (delegate, ctx, req) -> {
                    requestLogAtomicReference.set(ctx.log());
                    ctx.initiateConnectionShutdown().whenComplete((ignore, ex) -> completed.set(true));
                    return delegate.execute(ctx, req);
                }).build();

        final CompletableFuture<AggregatedHttpResponse> res = client.get("/delayed_streaming").aggregate();
        await().until(completed::get);
        assertThat(requestLogAtomicReference.get().ensureRequestComplete().requestHeaders()
                                            .get("connection")).isEqualTo("close");
        assertThat(res.get().contentUtf8()).isEqualTo(
                "Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!");
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testDisconnectConnectionAfterConnected(SessionProtocol protocol) throws Exception {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<RequestLogAccess> requestLogAtomicReference = new AtomicReference<>();

        final WebClient client = WebClient.builder(server.uri(protocol)).decorator((delegate, ctx, req) -> {
            requestLogAtomicReference.set(ctx.log());
            final HttpResponse res = delegate.execute(ctx, req);
            ctx.log().whenRequestComplete().thenAccept(
                    it -> ctx.initiateConnectionShutdown().whenComplete((ignore, ex) -> completed.set(true)));
            return res;
        }).build();

        final CompletableFuture<AggregatedHttpResponse> res = client.get("/plaintext").aggregate();
        await().until(completed::get);
        assertThat(res.get().contentUtf8()).isEqualTo("Hello, World!");
        assertThat(requestLogAtomicReference.get().ensureRequestComplete().requestHeaders()
                                            .get("connection")).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testDisconnectConnectionAfterConnectedWithDelayedStreaming(SessionProtocol protocol) throws Exception {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<RequestLogAccess> requestLogAtomicReference = new AtomicReference<>();

        final WebClient client = WebClient.builder(server.uri(protocol)).decorator((delegate, ctx, req) -> {
            requestLogAtomicReference.set(ctx.log());
            final HttpResponse res = delegate.execute(ctx, req);
            ctx.log().whenRequestComplete().thenAccept(
                    it -> ctx.initiateConnectionShutdown().whenComplete((ignore, ex) -> completed.set(true)));
            return res;
        }).build();

        final CompletableFuture<AggregatedHttpResponse> res = client.get("/delayed_streaming").aggregate();
        await().until(completed::get);
        assertThat(res.get().contentUtf8()).isEqualTo(
                "Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!");
        assertThat(requestLogAtomicReference.get().ensureRequestComplete().requestHeaders()
                                            .get("connection")).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    void testDisconnectConnectionAfterConnectedInSameConnection(SessionProtocol protocol) throws Exception {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final Set<RequestLogAccess> requestLogAccesses = Sets.newHashSet();

        final ClientFactory clientFactory = ClientFactory.builder().workerGroup(1).useHttp1Pipelining(true)
                                                         .build();
        final WebClient client = WebClient.builder(server.uri(protocol)).factory(clientFactory).decorator(
                (delegate, ctx, req) -> {
                    requestLogAccesses.add(ctx.log());
                    final HttpResponse res = delegate.execute(ctx, req);
                    ctx.log().whenRequestComplete().thenAccept(
                            it -> ctx.initiateConnectionShutdown()
                                     .whenComplete((ignore, ex) -> completed.set(true)));
                    return res;
                }).build();

        final CompletableFuture<AggregatedHttpResponse> res1 = client.get("/plaintext").aggregate();
        final CompletableFuture<AggregatedHttpResponse> res2 = client.get("/delayed_streaming").aggregate();
        await().until(completed::get);
        assertThat(res1.get().contentUtf8()).isEqualTo("Hello, World!");
        assertThat(res2.get().contentUtf8()).isEqualTo(
                "Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!Hello, World!");
        assertThat(requestLogAccesses.stream().findAny().get().ensureRequestComplete().requestHeaders()
                                     .get("connection")).isNull();
    }
}
