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
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.consul.ConsulTestBase;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.internal.consul.CatalogClient.Node;
import com.linecorp.armeria.server.Server;

public class CatalogClientTest extends ConsulTestBase {

    @Nullable
    static List<Server> servers;

    // Creates consul client instances.
    final AgentServiceClient agent = AgentServiceClient.of(client());
    final CatalogClient catalog = CatalogClient.of(client());

    @BeforeAll
    public static void setup() {
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
    public static void cleanup() throws Exception {
        assert servers != null;
        servers.forEach(Server::close);
        servers = null;
    }

    @Test
    public void testCatalogClient() {
        // Register service endpoints.
        sampleEndpoints.forEach(endpoint -> {
                                    try {
                                        agent.register(serviceName, endpoint.host(), endpoint.port(),
                                                       null, QueryParams.of()).join();
                                    } catch (JsonProcessingException e) {
                                        fail(e.getMessage());
                                    }
                                }
        );
        // Get registered service endpoints.
        final List<Node> nodes = catalog.service(serviceName, QueryParams.of()).join();

        // Confirm registered service endpoints.
        assertThat(nodes).isNotNull();
        assertThat(nodes.size()).isEqualTo(sampleEndpoints.size());
        assertThat(
                sampleEndpoints.stream().allMatch(
                        endpoint -> nodes.stream().anyMatch(
                                node -> Objects.equals(node.serviceAddress, endpoint.host()) &&
                                        node.servicePort == endpoint.port()))
        ).isTrue();
    }
}
