/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.server.Server;

import io.netty.channel.Channel;

class Http2CancellationTest {

    static Stream<Arguments> cancellationModes() {
        return Stream.of(SessionProtocol.H2C, SessionProtocol.H2)
                .flatMap(protocol -> Stream.of(false, true)
                        .flatMap(cancelContext -> Stream.of(false, true)
                                .map(singleStream -> Arguments.of(protocol, cancelContext, singleStream))));
    }

    @ParameterizedTest
    @MethodSource("cancellationModes")
    void cancellationPreservesConnection(SessionProtocol protocol, boolean cancelContext,
                                         boolean singleStream) throws Exception {
        final BlockingQueue<HttpResponseWriter> pending = new LinkedBlockingQueue<>();
        final Server server = Server.builder().http(0).https(0).tlsSelfSigned()
                .http2MaxStreamsPerConnection(singleStream ? 1 : 2)
                .service("/pending", (ctx, req) -> {
                    final HttpResponseWriter response = HttpResponse.streaming();
                    pending.add(response);
                    return response;
                })
                .service("/ok", (ctx, req) -> HttpResponse.of("ok"))
                .build();
        server.start().join();
        try (ClientFactory factory = ClientFactory.builder().workerGroup(1).tlsNoVerify().build()) {
            final AtomicReference<HttpResponse> cancelledResponse = new AtomicReference<>();
            final AtomicReference<ClientRequestContext> cancelledContext = new AtomicReference<>();
            final AtomicReference<ClientRequestContext> lastContext = new AtomicReference<>();
            final IllegalStateException cause = new IllegalStateException("cancelled response");
            final int port = server.activeLocalPort(protocol.isTls() ? SessionProtocol.HTTPS
                                                                    : SessionProtocol.HTTP);
            final WebClient client = WebClient.builder(protocol.uriText() + "://127.0.0.1:" + port)
                    .factory(factory)
                    .decorator((delegate, ctx, req) -> {
                        lastContext.set(ctx);
                        if (!req.path().equals("/pending") || cancelledContext.get() != null) {
                            return delegate.execute(ctx, req);
                        }
                        cancelledContext.set(ctx);
                        final RequestLogBuilder log =
                                mock(RequestLogBuilder.class, delegatesTo(ctx.logBuilder()));
                        doAnswer(invocation -> {
                            ctx.logBuilder().startResponse();
                            if (cancelContext) {
                                ctx.cancel(cause);
                            } else {
                                cancelledResponse.get().abort(cause);
                            }
                            return null;
                        }).when(log).startResponse();
                        final HttpResponse response = delegate.execute(new ClientRequestContextWrapper(ctx) {
                            @Override
                            public RequestLogBuilder logBuilder() {
                                return log;
                            }
                        }, req);
                        cancelledResponse.set(response);
                        return response;
                    }).build();
            final HttpResponse cancelled = client.get("/pending");
            final HttpResponseWriter serverCancelled = pending.poll(5, TimeUnit.SECONDS);
            assertThat(serverCancelled).isNotNull();
            final Channel channel = cancelledContext.get().log().partial().channel();
            assertThat(channel).isNotNull();

            final CompletableFuture<AggregatedHttpResponse> victim;
            final HttpResponseWriter serverVictim;
            if (singleStream) {
                victim = null;
                serverVictim = null;
            } else {
                victim = client.get("/pending").aggregate();
                serverVictim = pending.poll(5, TimeUnit.SECONDS);
                assertThat(serverVictim).isNotNull();
                assertThat(lastContext.get().log().partial().channel()).isSameAs(channel);
            }
            serverCancelled.write(ResponseHeaders.of(HttpStatus.OK));
            serverCancelled.close();
            assertThat(cancelled.aggregate().handle((res, failure) -> failure).get(5, TimeUnit.SECONDS))
                    .isSameAs(cause);
            assertThat(cancelledContext.get().log().whenComplete().get(5, TimeUnit.SECONDS).responseCause())
                    .isSameAs(cause);
            if (serverVictim != null) {
                serverVictim.write(ResponseHeaders.of(HttpStatus.OK));
                serverVictim.close();
                assertThat(victim.get(5, TimeUnit.SECONDS).status()).isEqualTo(HttpStatus.OK);
            }
            assertThat(client.get("/ok").aggregate().get(5, TimeUnit.SECONDS).contentUtf8()).isEqualTo("ok");
            assertThat(lastContext.get().log().partial().channel()).isSameAs(channel);
            assertThat(channel.isActive()).isTrue();
        } finally {
            server.stop().join();
        }
    }
}
