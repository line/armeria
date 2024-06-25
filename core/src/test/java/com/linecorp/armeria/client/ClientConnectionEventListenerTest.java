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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

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

            final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/delayed");
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
}
