/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.server.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.websocket.WebSocketServiceTest.AbstractWebSocketHandler;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderValues;

class WebSocketServiceHandshakeTest {

    private static final AtomicBoolean threadRescheduling = new AtomicBoolean();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.tlsSelfSigned()
              .http(0)
              .https(0)
              .requestTimeoutMillis(0);
            sb.service("/chat", WebSocketService.builder(new AbstractWebSocketHandler())
                                                .subprotocols("chat", "superchat")
                                                .allowedOrigins("foo.com")
                                                .build());
            sb.decorator((delegate, ctx, req) -> {
                if (!threadRescheduling.get()) {
                    return delegate.serve(ctx, req);
                }
                return HttpResponse.of(() -> {
                    try {
                        return delegate.serve(ctx, req);
                    } catch (Exception e) {
                        // should never reach here.
                        throw new Error();
                    }
                }, ctx.blockingTaskExecutor());
            });
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @CsvSource({
            "H1C, true",
            "H1C, false",
            "H1,  true",
            "H1,  false",
    })
    @ParameterizedTest
    void http1Handshake(SessionProtocol sessionProtocol, boolean useThreadReschedulingDecorator)
            throws InterruptedException {
        // If H1 protocol is used, HttpServerUpgradeHandler is involved in the pipeline.
        threadRescheduling.set(useThreadReschedulingDecorator);
        final WebClient client = WebClient.builder(server.uri(sessionProtocol))
                                          .factory(ClientFactory.insecure())
                                          .decorator(LoggingClient.builder().newDecorator())
                                          .responseTimeoutMillis(0)
                                          .build();
        final RequestHeadersBuilder headersBuilder =
                RequestHeaders.builder(HttpMethod.GET, "/chat")
                              .add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE.toString());
        final Channel channel;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.execute(headersBuilder.build()).aggregate().join();

            channel = channel(captor.get());
            assertThat(res.status()).isSameAs(HttpStatus.BAD_REQUEST);
            assertThat(res.contentUtf8()).contains("The upgrade header must contain:",
                                                   "Upgrade: websocket",
                                                   "Connection: Upgrade");
        }

        headersBuilder.add(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toString());
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.execute(headersBuilder.build()).aggregate().join();

            // The same connection is used.
            assertThat(channel).isSameAs(channel(captor.get()));
            assertThat(res.status()).isSameAs(HttpStatus.FORBIDDEN);
        }

        headersBuilder.add(HttpHeaderNames.ORIGIN, "foo.com");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.execute(headersBuilder.build()).aggregate().join();

            // The previous connection is closed.
            assertThat(channel).isNotSameAs(channel(captor.get()));
            assertThat(res.status()).isSameAs(HttpStatus.BAD_REQUEST);
            assertThat(res.headers().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION)).isEqualTo("13");
        }

        headersBuilder.addInt(HttpHeaderNames.SEC_WEBSOCKET_VERSION, 13);
        final AggregatedHttpResponse res = client.execute(headersBuilder.build()).aggregate().join();

        assertThat(res.status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).contains("missing Sec-WebSocket-Key header");

        // Borrowed from the RFC. https://datatracker.ietf.org/doc/html/rfc6455#section-1.2
        headersBuilder.add(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==")
                      .add(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "superchat");
        final CompletableFuture<ResponseHeaders> informationalHeadersFuture = new CompletableFuture<>();
        client.execute(headersBuilder.build()).subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                informationalHeadersFuture.complete((ResponseHeaders) httpObject);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });

        final ResponseHeaders responseHeaders = informationalHeadersFuture.join();
        assertThat(responseHeaders.status()).isSameAs(HttpStatus.SWITCHING_PROTOCOLS);
        assertThat(responseHeaders.get(HttpHeaderNames.CONNECTION))
                .isEqualTo(HttpHeaderValues.UPGRADE.toString());
        assertThat(responseHeaders.get(HttpHeaderNames.UPGRADE))
                .isEqualTo(HttpHeaderValues.WEBSOCKET.toString());
        assertThat(responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT))
                .isEqualTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
        assertThat(responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL))
                .isEqualTo("superchat");
    }

    private static Channel channel(ClientRequestContext ctx) {
        final Channel channel = ctx.log().whenAvailable(RequestLogProperty.SESSION).join().channel();
        assertThat(channel).isNotNull();
        return channel;
    }

    @CsvSource({ "H2C", "H2" })
    @ParameterizedTest
    void http2Handshake(SessionProtocol sessionProtocol) {
        final WebClient client = WebClient.builder(server.uri(sessionProtocol))
                                          .factory(ClientFactory.insecure())
                                          .decorator(LoggingClient.builder().newDecorator())
                                          .responseTimeoutMillis(0)
                                          .build();
        final AggregatedHttpResponse res = client.get("/chat").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);

        final RequestHeadersBuilder headersBuilder =
                RequestHeaders.builder(HttpMethod.CONNECT, "/chat")
                              .add(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString());
        SplitHttpResponse split = client.execute(headersBuilder.build()).split();
        ResponseHeaders responseHeaders = split.headers().join();
        split.body().abort();
        assertThat(responseHeaders.status()).isSameAs(HttpStatus.FORBIDDEN);

        headersBuilder.add(HttpHeaderNames.ORIGIN, "foo.com");
        split = client.execute(headersBuilder.build()).split();
        responseHeaders = split.headers().join();
        split.body().abort();
        assertThat(responseHeaders.status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThat(responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_VERSION)).isEqualTo("13");

        headersBuilder.addInt(HttpHeaderNames.SEC_WEBSOCKET_VERSION, 13)
                      .add(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "superchat");
        split = client.execute(headersBuilder.build()).split();
        responseHeaders = split.headers().join();
        split.body().abort();
        assertThat(responseHeaders.status()).isSameAs(HttpStatus.OK);
        assertThat(responseHeaders.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)).isEqualTo("superchat");
    }
}
