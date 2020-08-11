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
package com.linecorp.armeria.internal.consul;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.consul.CatalogClient.Node;
import com.linecorp.armeria.server.Server;

class CatalogClientTest extends ConsulTestBase {

    @Nullable
    static List<Server> servers;

    // Creates consul client instances.
    final AgentServiceClient agent = AgentServiceClient.of(client());
    final CatalogClient catalog = CatalogClient.of(client());

    @BeforeAll
    static void setup() {
        servers = new ArrayList<>();
        sampleEndpoints.forEach(endpoint -> {
            final Server server = Server.builder()
                                        .http(endpoint.port())
                                        .service("/", new EchoService())
                                        .build();
            servers.add(server);
        });
    }

    @AfterAll
    static void cleanup() throws Exception {
        servers.forEach(Server::close);
    }

    @Test
    void testCatalogClient() throws JsonProcessingException {
        // Register service endpoints.
        for (Endpoint sampleEndpoint : sampleEndpoints) {
            final String serviceId =
                    serviceName + '.' + Long.toHexString(ThreadLocalRandom.current().nextLong());
            agent.register(serviceId, serviceName, sampleEndpoint.host(), sampleEndpoint.port(), null)
                 .aggregate().join();
        }
        // Get registered service endpoints.
        final List<Node> nodes = catalog.service(serviceName).join();

        // Confirm registered service endpoints.
        assertThat(nodes).isNotNull();
        assertThat(nodes).hasSameSizeAs(sampleEndpoints);
        assertThat(sampleEndpoints.stream().allMatch(
                endpoint -> nodes.stream().anyMatch(node -> node.serviceAddress.equals(endpoint.host()) &&
                                                            node.servicePort == endpoint.port()))
        ).isTrue();
    }
}
