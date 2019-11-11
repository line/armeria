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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.consul.ConsulTestBase;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.consul.ConsulUpdatingListener;

public class ConsulEndpointGroupTest extends ConsulTestBase {

    @Nullable
    static List<Server> servers;

    @BeforeAll
    static void startServers() {
        servers = new ArrayList<>();

        for (Endpoint endpoint : sampleEndpoints) {
            final Server server = Server.builder()
                                        .http(endpoint.port())
                                        .service("/", new EchoService())
                                        .build();
            final ServerListener listener = ConsulUpdatingListener.builder(serviceName)
                                                                  .consulUri(client().uri().toString())
                                                                  .endpoint(endpoint)
                                                                  .build();
            server.addListener(listener);
            server.start().join();
            servers.add(server);
        }
    }

    @AfterAll
    static void stopServers() throws Exception {
        servers.forEach(Server::close);
    }

    @Test
    void testConsulEndpointGroupWithClient() {
        try (ConsulEndpointGroup endpointGroup =
                     ConsulEndpointGroup.builder(serviceName)
                                        .consulClient(client())
                                        .registryFetchIntervalSeconds(1)
                                        .build()) {
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints);
                   });
            // stop a server
            servers.get(0).stop();
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSize(sampleEndpoints.size() - 1);
                   });
            // restart the server
            servers.get(0).start();
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints);
                   });
        }
    }

    @Test
    void testConsulEndpointGroupWithUrl() {
        try (ConsulEndpointGroup endpointGroup =
                     ConsulEndpointGroup.builder(serviceName)
                                        .consulUri(client().uri().toString())
                                        .registryFetchInterval(Duration.ofSeconds(1))
                                        .build()) {
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints);
                   });
            // stop a server
            servers.get(0).stop().join();
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSize(sampleEndpoints.size() - 1);
                   });
            // restart the server
            servers.get(0).start().join();
            await().atMost(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints);
                   });
        }
    }
}
