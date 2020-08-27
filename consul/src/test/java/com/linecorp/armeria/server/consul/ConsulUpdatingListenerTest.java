/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.consul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.internal.consul.ConsulTestBase;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;

class ConsulUpdatingListenerTest extends ConsulTestBase {

    static final List<Server> servers = new ArrayList<>();

    @BeforeAll
    static void startServers() throws JsonProcessingException {
        for (Endpoint endpoint : sampleEndpoints) {
            final Server server = Server.builder()
                                        .http(endpoint.port())
                                        .service("/echo", new EchoService())
                                        .build();
            final ServerListener listener =
                    ConsulUpdatingListener.builder(serviceName)
                                          .consulPort(consul().getHttpPort())
                                          .endpoint(endpoint)
                                          .checkUri("http://" + endpoint.host() +
                                                    ':' + endpoint.port() + "/echo")
                                          .checkMethod(HttpMethod.POST)
                                          .checkInterval(Duration.ofSeconds(1))
                                          .build();
            server.addListener(listener);
            server.start().join();
            servers.add(server);
        }
    }

    @AfterAll
    static void stopServers() throws Exception {
        servers.forEach(Server::close);
        servers.clear();
    }

    @Test
    void testBuild() {
        assertThat(ConsulUpdatingListener.builder(serviceName).build()).isNotNull();
        assertThat(ConsulUpdatingListener.builder(serviceName)
                                         .build()).isNotNull();
    }

    @Test
    void shouldRaiseExceptionWhenCheckUrlMissed() {
        assertThatThrownBy(ConsulUpdatingListener.builder(serviceName)
                                                 .checkMethod(HttpMethod.POST)
                                                 .checkIntervalMillis(1000)::build
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testEndpointsCountOfListeningServiceWithAServerStopAndStart() {
        // Checks sample endpoints created when initialized.
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                      assertThat(client().endpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints));

        // When we close one server then the listener deregister it automatically from consul agent.
        servers.get(0).stop().join();

        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   final List<Endpoint> results = client().endpoints(serviceName).join();
                   assertThat(results).hasSize(sampleEndpoints.size() - 1);
               });

        // Endpoints increased after service restart.
        servers.get(0).start().join();

        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                      assertThat(client().endpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints));
    }

    @Test
    void testHealthyServiceWithAdditionalCheckRule() {
        // Checks sample endpoints created when initialized.
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                     assertThat(client().healthyEndpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints));

        // Make a service to produce 503 error for checking by consul.
        sampleEndpoints.stream()
                       .findFirst()
                       .ifPresent(e -> {
                           final WebClient webClient = WebClient.of("http://" + e.host() + ':' + e.port());
                           webClient.post("echo", "503")
                                    .aggregate()
                                    .join();
                       });

        // And then, consul marks the service to an unhealthy state.
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                      assertThat(client().healthyEndpoints(serviceName).join())
                              .hasSize(sampleEndpoints.size() - 1));

        // But, the size of endpoints does not changed.
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                      assertThat(client().endpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints));

        // Make a service to produce 200 OK for checking by consul.
        sampleEndpoints.stream()
                       .findFirst()
                       .ifPresent(e -> {
                           final WebClient webClient = WebClient.of("http://" + e.host() + ':' + e.port());
                           webClient.post("echo", "200")
                                    .aggregate()
                                    .join();
                       });
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                      assertThat(client().healthyEndpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints));
    }
}
