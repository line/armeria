/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client.consul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.consul.ConsulTestBase;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.consul.ConsulUpdatingListener;

class ConsulEndpointGroupTest extends ConsulTestBase {

    static final List<Server> servers = new ArrayList<>();

    @BeforeAll
    static void startServers() {

        for (Endpoint endpoint : sampleEndpoints) {
            final Server server = Server.builder()
                                        .http(endpoint.port())
                                        .service("/", new EchoService())
                                        .build();
            final ServerListener listener =
                    ConsulUpdatingListener.builder(URI.create("http://127.0.0.1:" + consul().getHttpPort()),
                                                   serviceName)
                                          .consulToken(CONSUL_TOKEN)
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
    void testConsulEndpointGroupWithClient() {
        try (ConsulEndpointGroup endpointGroup =
                     ConsulEndpointGroup.builder(URI.create("http://127.0.0.1:" + consul().getHttpPort()),
                                                 serviceName)
                                        .consulApiVersion("v1")
                                        .consulToken(CONSUL_TOKEN)
                                        .registryFetchIntervalMillis(1000)
                                        .build()) {
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() ->
                                  assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints));
            // stop a server
            servers.get(0).stop();
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() ->
                                  assertThat(endpointGroup.endpoints()).hasSize(sampleEndpoints.size() - 1));
            // restart the server
            servers.get(0).start();
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() ->
                                  assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints));
        }
    }

    @Test
    void testConsulEndpointGroupWithUrl() {
        try (ConsulEndpointGroup endpointGroup =
                     ConsulEndpointGroup.builder(URI.create("http://127.0.0.1:" + consul().getHttpPort()),
                                                 serviceName)
                                        .consulToken(CONSUL_TOKEN)
                                        .registryFetchInterval(Duration.ofSeconds(1))
                                        .build()) {
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() ->
                                  assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints));
            // stop a server
            servers.get(0).stop().join();
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() ->
                                  assertThat(endpointGroup.endpoints()).hasSize(sampleEndpoints.size() - 1));
            // restart the server
            servers.get(0).start().join();
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() ->
                                  assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints));
        }
    }

    @Test
    void testSelectStrategy() {
        try (ConsulEndpointGroup endpointGroup =
                     ConsulEndpointGroup.builder(URI.create("http://127.0.0.1:" + consul().getHttpPort()),
                                                 serviceName)
                                        .consulToken(CONSUL_TOKEN)
                                        .registryFetchInterval(Duration.ofSeconds(1))
                                        .build()) {
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.selectNow(null))
                           .isNotEqualTo(endpointGroup.selectNow(null)));
        }
    }

    @Test
    void testConsulEndpointGroupWithDatacenter() {
        final ConsulEndpointGroupBuilder builder =
                ConsulEndpointGroup.builder(URI.create("http://127.0.0.1:" + consul().getHttpPort()),
                                            serviceName)
                                   .consulApiVersion("v1")
                                   .consulToken(CONSUL_TOKEN)
                                   .registryFetchIntervalMillis(1000);
        // default datacenter
        try (ConsulEndpointGroup endpointGroup = builder.datacenter("dc1").build()) {
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints));
        }
        // non-existent datacenter
        try (ConsulEndpointGroup endpointGroup = builder.datacenter("dc2").build()) {
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints()).isEmpty());
        }
    }

    @Test
    void testConsulEndpointGroupWithFilter() {
        // filter to first endpoint using port
        final Endpoint endpoint =
                sampleEndpoints.stream()
                               .findFirst()
                               .orElseThrow(() -> new IllegalArgumentException("No sample endpoints."));
        try (ConsulEndpointGroup endpointGroup =
                     ConsulEndpointGroup.builder(URI.create("http://127.0.0.1:" + consul().getHttpPort()),
                                                 serviceName)
                                        .consulToken(CONSUL_TOKEN)
                                        .registryFetchInterval(Duration.ofSeconds(1))
                                        .filter("ServicePort == " + endpoint.port())
                                        .build()) {
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSize(1));
        }
    }
}
