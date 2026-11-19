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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ServerPendingResponsesMetricsTest {

    private static final String PENDING_RESPONSES = "armeria.server.pending.responses";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void countsPendingResponsesAcrossServersWithSharedRegistry() {
        final CompletableFuture<HttpResponse> firstResponse = new CompletableFuture<>();
        final CompletableFuture<HttpResponse> secondResponse = new CompletableFuture<>();
        try (Server first = newServer(registry, firstResponse);
             Server second = newServer(registry, secondResponse)) {
            first.start().join();
            second.start().join();
            final Gauge gauge = registry.get(PENDING_RESPONSES).gauge();

            final CompletableFuture<AggregatedHttpResponse> firstResult = request(first);
            await().untilAsserted(() -> assertThat(gauge.value()).isOne());
            final CompletableFuture<AggregatedHttpResponse> secondResult = request(second);
            await().untilAsserted(() -> assertThat(gauge.value()).isEqualTo(2));

            firstResponse.complete(HttpResponse.of(HttpStatus.OK));
            assertThat(firstResult.join().status()).isSameAs(HttpStatus.OK);
            first.stop().join();
            assertThat(gauge.value()).isOne();

            secondResponse.complete(HttpResponse.of(HttpStatus.OK));
            assertThat(secondResult.join().status()).isSameAs(HttpStatus.OK);
            second.stop().join();
            assertThat(gauge.value()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void countsPendingResponsesAfterServerReplacement(boolean clearRegistry) {
        try (Server first = newServer(registry, UnmodifiableFuture.completedFuture(HttpResponse.of(200)))) {
            first.start().join();
        }
        if (clearRegistry) {
            registry.clear();
        }
        final CompletableFuture<HttpResponse> response = new CompletableFuture<>();
        try (Server second = newServer(registry, response)) {
            second.start().join();

            final CompletableFuture<AggregatedHttpResponse> result = request(second);
            final Gauge gauge = registry.get(PENDING_RESPONSES).gauge();
            await().untilAsserted(() -> assertThat(gauge.value()).isOne());

            response.complete(HttpResponse.of(HttpStatus.OK));
            assertThat(result.join().status()).isSameAs(HttpStatus.OK);
            await().untilAsserted(() -> assertThat(gauge.value()).isZero());
        }
    }

    private static Server newServer(SimpleMeterRegistry registry, CompletableFuture<HttpResponse> response) {
        return Server.builder()
                     .http(new InetSocketAddress("127.0.0.1", 0))
                     .meterRegistry(registry)
                     .requestTimeoutMillis(0)
                     .service("/", (ctx, req) -> HttpResponse.from(response))
                     .build();
    }

    private static CompletableFuture<AggregatedHttpResponse> request(Server server) {
        return WebClient.builder("http://127.0.0.1:" + server.activeLocalPort())
                        .responseTimeoutMillis(0)
                        .build()
                        .get("/")
                        .aggregate();
    }
}
