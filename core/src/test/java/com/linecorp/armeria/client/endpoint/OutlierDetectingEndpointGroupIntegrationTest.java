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

package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class OutlierDetectingEndpointGroupIntegrationTest {

    private static final ConcurrentMap<Integer, AtomicInteger> requestCounter = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, Boolean> badServers = new ConcurrentHashMap<>();

    private static ServerExtension server(int id) {
        return new ServerExtension() {
            @Override
            protected void configure(ServerBuilder sb) {
                sb.http(0);
                sb.service("/ping", (HttpService) (ctx, req) -> {
                    requestCounter.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                    if (Boolean.TRUE.equals(badServers.get(id))) {
                        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                    return HttpResponse.of(HttpStatus.OK);
                });
            }
        };
    }

    @RegisterExtension
    static final ServerExtension server1 = server(1);
    @RegisterExtension
    static final ServerExtension server2 = server(2);
    @RegisterExtension
    static final ServerExtension server3 = server(3);

    @AfterEach
    void clearState() {
        requestCounter.clear();
        badServers.clear();
    }

    private static Endpoint endpoint(ServerExtension server) {
        return Endpoint.of("127.0.0.1", server.httpPort());
    }

    @Test
    void asHttpDecoratorMarksFailingServersAsBad() {
        badServers.put(1, true);

        final List<Endpoint> endpoints =
                ImmutableList.of(endpoint(server1), endpoint(server2), endpoint(server3));
        try (OutlierDetectingEndpointGroup endpointGroup =
                     OutlierDetectingEndpointGroup.builder(EndpointGroup.of(endpoints))
                                                  // Trip the breaker on the first failure.
                                                  .minimumRequestThreshold(1)
                                                  .counterUpdateIntervalMillis(1)
                                                  .failureRateThreshold(0.99)
                                                  // Long enough that the bad endpoint stays bad for
                                                  // the duration of the test.
                                                  .circuitOpenWindowMillis(30_000)
                                                  .build()) {

            endpointGroup.whenReady().join();

            final WebClient client =
                    WebClient.builder(SessionProtocol.HTTP, endpointGroup)
                             .decorator(endpointGroup.asDecorator())
                             .build();

            // Drive enough traffic for the round-robin selector to hit every server, including the bad one.
            for (int i = 0; i < 60; i++) {
                client.get("/ping").aggregate().join();
            }

            // The bad server should have been ejected from the rotation.
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(endpointGroup.endpoints()).doesNotContain(endpoint(server1)));

            // Subsequent traffic should not reach server1 anymore.
            requestCounter.get(1).set(0);
            for (int i = 0; i < 30; i++) {
                client.get("/ping").aggregate().join();
            }
            assertThat(requestCounter.get(1).get()).isZero();
            assertThat(requestCounter.get(2).get()).isPositive();
            assertThat(requestCounter.get(3).get()).isPositive();
        }
    }

    @Test
    void customSuccessFunctionDrivesCircuitBreakerClassification() {
        // server1 returns 500, but the custom SuccessFunction treats every response as a success, so the
        // circuit breaker should never open and server1 should remain in the rotation.
        badServers.put(1, true);

        final List<Endpoint> endpoints =
                ImmutableList.of(endpoint(server1), endpoint(server2));
        try (OutlierDetectingEndpointGroup endpointGroup =
                     OutlierDetectingEndpointGroup.builder(EndpointGroup.of(endpoints))
                                                  .successFunction((ctx, log) -> true)
                                                  .minimumRequestThreshold(1)
                                                  .counterUpdateIntervalMillis(1)
                                                  .failureRateThreshold(0.5)
                                                  .circuitOpenWindowMillis(30_000)
                                                  .build()) {

            endpointGroup.whenReady().join();

            final WebClient client =
                    WebClient.builder(SessionProtocol.HTTP, endpointGroup)
                             .decorator(endpointGroup.asDecorator())
                             .build();

            for (int i = 0; i < 40; i++) {
                client.get("/ping").aggregate().join();
            }

            // Both endpoints should still be alive — the failing server was classified as success.
            assertThat(endpointGroup.endpoints()).containsExactlyInAnyOrder(
                    endpoint(server1), endpoint(server2));
            assertThat(requestCounter.get(1).get()).isPositive();
            assertThat(requestCounter.get(2).get()).isPositive();
        }
    }

    @Test
    void recoversAfterBadEndpointWindowExpires() {
        badServers.put(1, true);

        final List<Endpoint> endpoints =
                ImmutableList.of(endpoint(server1), endpoint(server2));
        final long circuitOpenWindowMillis = 1500;
        try (OutlierDetectingEndpointGroup endpointGroup =
                     OutlierDetectingEndpointGroup.builder(EndpointGroup.of(endpoints))
                                                  .minimumRequestThreshold(1)
                                                  .counterUpdateIntervalMillis(1)
                                                  .failureRateThreshold(0.99)
                                                  .circuitOpenWindowMillis(circuitOpenWindowMillis)
                                                  .build()) {

            endpointGroup.whenReady().join();
            final WebClient client =
                    WebClient.builder(SessionProtocol.HTTP, endpointGroup)
                             .decorator(endpointGroup.asDecorator())
                             .build();

            for (int i = 0; i < 40; i++) {
                client.get("/ping").aggregate().join();
            }

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(endpointGroup.endpoints()).doesNotContain(endpoint(server1)));

            // Heal the bad server before the cool-down expires.
            badServers.remove(1);

            await().atMost(Duration.ofSeconds(circuitOpenWindowMillis * 4 / 1000 + 5))
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints())
                           .containsExactlyInAnyOrder(endpoint(server1), endpoint(server2)));
        }
    }
}
