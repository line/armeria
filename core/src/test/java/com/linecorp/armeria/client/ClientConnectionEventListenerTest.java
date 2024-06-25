/*
 * Copyright 2024 LY Corporation
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
import static org.awaitility.Awaitility.await;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.HttpChannelPool.PoolKey;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ClientConnectionTimingsBuilder;
import com.linecorp.armeria.internal.testing.NettyServerExtension;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Promise;

class ClientConnectionEventListenerTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.idleTimeoutMillis(2000);
            sb.requestTimeoutMillis(0);
            sb.service("/delayed",
                       (ctx, req) -> HttpResponse.delayed(HttpResponse.builder()
                                                                      .ok()
                                                                      .content("OK")
                                                                      .build(), Duration.ofMillis(500)));
        }
    };

    @RegisterExtension
    static NettyServerExtension http1Server = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new LoggingHandler(getClass()));
            ch.pipeline().addLast(new HttpServerCodec());
        }
    };

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void requestWithKeepAliveHandler(SessionProtocol protocol) throws InterruptedException {
        final CountingClientConnectionEventListener connectionEventListener =
                new CountingClientConnectionEventListener();

        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .idleTimeoutMillis(1000L)
                                                        .connectionEventListener(connectionEventListener)
                                                        .build()) {
            final WebClient webClient = WebClient
                    .builder(protocol.uriText() + "://127.0.0.1:" + server.httpPort())
                    .factory(clientFactory)
                    .build();

            final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/delayed");
            final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request).aggregate();

            await().untilAsserted(
                    () -> assertThat(connectionEventListener.active(protocol)).isEqualTo(1));
            assertThat(connectionEventListener.failed(protocol)).isEqualTo(0);
            assertThat(connectionEventListener.pending(protocol)).isEqualTo(1);
            assertThat(connectionEventListener.opened(protocol)).isEqualTo(1);
            assertThat(connectionEventListener.idle(protocol)).isEqualTo(0);

            await().untilAsserted(
                    () -> assertThat(connectionEventListener.idle(protocol)).isEqualTo(1));

            // after idle timeout
            await().untilAsserted(
                    () -> assertThat(connectionEventListener.closed(protocol)).isEqualTo(1));
        }
    }

    @CsvSource({ "H1C", "H2C" })
    @ParameterizedTest
    void requestWithoutKeepAliveHandler(SessionProtocol protocol) throws InterruptedException {
        final CountingClientConnectionEventListener connectionEventListener =
                new CountingClientConnectionEventListener();

        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .idleTimeoutMillis(0)
                                                        .connectionEventListener(connectionEventListener)
                                                        .build()) {
            final WebClient webClient = WebClient
                    .builder(protocol.uriText() + "://127.0.0.1:" + server.httpPort())
                    .factory(clientFactory)
                    .build();

            final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/delayed");
            final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request).aggregate();

            await().untilAsserted(
                    () -> assertThat(connectionEventListener.opened(protocol)).isEqualTo(1));
            assertThat(connectionEventListener.pending(protocol)).isEqualTo(1);
            assertThat(connectionEventListener.failed(protocol)).isEqualTo(0);
            assertThat(connectionEventListener.active(protocol)).isEqualTo(0);
            assertThat(connectionEventListener.idle(protocol)).isEqualTo(0);

            response.join();

            await().untilAsserted(() -> assertThat(connectionEventListener.closed(protocol)).isEqualTo(1));
            assertThat(connectionEventListener.opened(protocol)).isEqualTo(1);
            assertThat(connectionEventListener.active(protocol)).isEqualTo(0);
            assertThat(connectionEventListener.idle(protocol)).isEqualTo(0);
        }
    }

    /**
     * In NIO transport mode (-Dcom.linecorp.armeria.transportType), the local address may not be
     * immediately available after invoking {@link Channel#connect(SocketAddress, ChannelPromise)} in
     * {@link HttpChannelPool#connect(SocketAddress, SessionProtocol, SerializationFormat, PoolKey,
     * Promise, ClientConnectionTimingsBuilder)}.
     */
    @CsvSource({
            "H1C,0,0",
            "H1C,1000,1",
            "H2C,0,0",
            "H2C,1000,1"
    })
    @ParameterizedTest
    void nioClientConnection(SessionProtocol protocol, Long idleTimeoutMillis, int expectedActiveOrIdleCount)
            throws InterruptedException {
        final CountingClientConnectionEventListener connectionEventListener =
                new CountingClientConnectionEventListener();
        final NioEventLoopGroup group = new NioEventLoopGroup();
        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .idleTimeoutMillis(idleTimeoutMillis)
                                                        .workerGroup(group, true)
                                                        .connectionEventListener(connectionEventListener)
                                                        .build()) {
            final WebClient webClient = WebClient
                    .builder(protocol.uriText() + "://127.0.0.1:" + server.httpPort())
                    .factory(clientFactory)
                    .build();

            final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/delayed");
            final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request).aggregate();

            await().untilAsserted(
                    () -> assertThat(connectionEventListener.opened(protocol)).isEqualTo(1));
            assertThat(connectionEventListener.pending(protocol)).isEqualTo(1);
            assertThat(connectionEventListener.failed(protocol)).isEqualTo(0);
            assertThat(connectionEventListener.active(protocol)).isEqualTo(expectedActiveOrIdleCount);
            assertThat(connectionEventListener.idle(protocol)).isEqualTo(0);

            response.join();

            await().untilAsserted(() -> assertThat(connectionEventListener.closed(protocol)).isEqualTo(1));
            assertThat(connectionEventListener.opened(protocol)).isEqualTo(1);
            assertThat(connectionEventListener.active(protocol)).isEqualTo(expectedActiveOrIdleCount);
            assertThat(connectionEventListener.idle(protocol)).isEqualTo(expectedActiveOrIdleCount);
        }
    }

    @Test
    void protocolUpgradeSuccess() {
        final SessionProtocol desiredProtocol = SessionProtocol.HTTP;
        final SessionProtocol actualProtocol = SessionProtocol.H2C;
        final CountingClientConnectionEventListener connectionEventListener =
                new CountingClientConnectionEventListener();

        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .idleTimeoutMillis(1000)
                                                        .useHttp2Preface(false)
                                                        .connectionEventListener(connectionEventListener)
                                                        .build()) {
            final WebClient webClient = WebClient
                    .builder(desiredProtocol.uriText() + "://127.0.0.1:" + server.httpPort())
                    .factory(clientFactory)
                    .build();

            final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
            final CompletableFuture<AggregatedHttpResponse> response = webClient.execute(request).aggregate();

            await().untilAsserted(
                    () -> assertThat(connectionEventListener.active(actualProtocol)).isEqualTo(1));
            assertThat(connectionEventListener.pending(desiredProtocol)).isEqualTo(1);
            assertThat(connectionEventListener.opened(actualProtocol)).isEqualTo(1);

            await().untilAsserted(
                    () -> assertThat(connectionEventListener.idle(actualProtocol)).isEqualTo(1));

            await().untilAsserted(
                    () -> assertThat(connectionEventListener.closed(actualProtocol)).isEqualTo(1));
        }
    }

    @Test
    void shouldBeFailedWhenRequestH2CtoH1CServer() throws Exception {
        final SessionProtocol desiredProtocol = SessionProtocol.H2C;
        final CountingClientConnectionEventListener connectionEventListener =
                new CountingClientConnectionEventListener();

        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .idleTimeoutMillis(1000)
                                                        .connectionEventListener(connectionEventListener)
                                                        .build()) {
            final WebClient webClient = WebClient
                    .builder(desiredProtocol.uriText() + "://127.0.0.1:" + http1Server.address().getPort())
                    .factory(clientFactory)
                    .build();

            final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/protocol-unsupported");

            webClient.execute(request).aggregate();

            await().untilAsserted(
                    () -> assertThat(connectionEventListener.failed(desiredProtocol)).isEqualTo(1));
            assertThat(connectionEventListener.pending(desiredProtocol)).isEqualTo(1);
            assertThat(connectionEventListener.opened(desiredProtocol)).isEqualTo(0);
            assertThat(connectionEventListener.active(desiredProtocol)).isEqualTo(0);
            assertThat(connectionEventListener.idle(desiredProtocol)).isEqualTo(0);
            assertThat(connectionEventListener.closed(desiredProtocol)).isEqualTo(0);
        }
    }
}
