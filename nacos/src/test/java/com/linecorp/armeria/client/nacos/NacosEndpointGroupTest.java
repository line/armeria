/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.client.nacos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.nacos.NacosTestBase;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.nacos.NacosUpdatingListener;

@GenerateNativeImageTrace
class NacosEndpointGroupTest extends NacosTestBase {

    private static final List<Server> servers = new ArrayList<>();
    private static volatile List<Endpoint> sampleEndpoints;

    private static final String DEFAULT_CLUSTER_NAME = "c1";

    @BeforeAll
    static void startServers() {
        await().pollInSameThread()
               .pollInterval(Duration.ofSeconds(1))
               .untilAsserted(() -> assertThatCode(() -> {
                   final List<Endpoint> endpoints = newSampleEndpoints();
                   servers.clear();
                   for (Endpoint endpoint : endpoints) {
                       final Server server = Server.builder()
                                                   .http(endpoint.port())
                                                   .service("/", new EchoService())
                                                   .build();
                       final ServerListener listener =
                               NacosUpdatingListener
                                       .builder(nacosUri(), serviceName)
                                       .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                                       .clusterName(DEFAULT_CLUSTER_NAME)
                                       .build();
                       server.addListener(listener);
                       server.start().join();
                       servers.add(server);
                   }
                   sampleEndpoints = endpoints;
               }).doesNotThrowAnyException());
    }

    @AfterAll
    static void stopServers() {
        servers.forEach(Server::close);
        servers.clear();
    }

    @Test
    void testNacosEndpointGroupWithClient() {
        try (NacosEndpointGroup endpointGroup =
                     NacosEndpointGroup.builder(nacosUri(), serviceName)
                                       .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                                       .registryFetchInterval(Duration.ofSeconds(1))
                                       .build()) {
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints);
                   });

            // stop a server
            servers.get(0).stop().join();
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSize(sampleEndpoints.size() - 1);
                   });

            // restart the server
            await().pollInSameThread().pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                // The port bound to the server could be stolen while stopping the server.
                assertThatCode(servers.get(0).start()::join).doesNotThrowAnyException();
            });
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints);
                   });
        }
    }

    @Test
    void testNacosEndpointGroupWithUrl() {
        try (NacosEndpointGroup endpointGroup =
                     NacosEndpointGroup.builder(nacosUri(), serviceName)
                                       .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                                       .registryFetchInterval(Duration.ofSeconds(1))
                                       .build()) {
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints);
                   });

            // stop a server
            servers.get(0).stop().join();
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSize(sampleEndpoints.size() - 1);
                   });

            // restart the server
            await().pollInSameThread().pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                // The port bound to the server could be stolen while stopping the server.
                assertThatCode(servers.get(0).start()::join).doesNotThrowAnyException();
            });
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> {
                       assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints);
                   });
        }
    }

    @Test
    void testSelectStrategy() {
        try (NacosEndpointGroup endpointGroup =
                     NacosEndpointGroup.builder(nacosUri(), serviceName)
                                       .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                                       .registryFetchInterval(Duration.ofSeconds(1))
                                       .build()) {
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.selectNow(null))
                           .isNotEqualTo(endpointGroup.selectNow(null)));
        }
    }

    @Test
    void testNacosEndpointGroupWithClusterName() {
        final NacosEndpointGroupBuilder builder =
                NacosEndpointGroup.builder(nacosUri(), serviceName)
                                  .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                                  .registryFetchInterval(Duration.ofSeconds(1));
        // default cluster name
        try (NacosEndpointGroup endpointGroup = builder.clusterName(DEFAULT_CLUSTER_NAME).build()) {
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints));
        }
        // non-existent cluster name
        try (NacosEndpointGroup endpointGroup = builder.clusterName("c2").build()) {
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints()).isEmpty());
        }
    }

    @Test
    void testNacosEndpointGroupWithNamespaceId() {
        final NacosEndpointGroupBuilder builder =
                NacosEndpointGroup.builder(nacosUri(), serviceName)
                                  .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                                  .registryFetchInterval(Duration.ofSeconds(1));
        // default namespace id
        try (NacosEndpointGroup endpointGroup = builder.namespaceId("public").build()) {
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints));
        }
        // non-existent namespace id
        try (NacosEndpointGroup endpointGroup = builder.namespaceId("private").build()) {
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints()).isEmpty());
        }
    }

    @Test
    void testNacosEndpointGroupWithGroupName() {
        final NacosEndpointGroupBuilder builder =
                NacosEndpointGroup.builder(nacosUri(), serviceName)
                                  .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                                  .registryFetchInterval(Duration.ofSeconds(1));
        try (NacosEndpointGroup endpointGroup = builder.build()) {
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints()).hasSameSizeAs(sampleEndpoints));
        }
        try (NacosEndpointGroup endpointGroup = builder.groupName("not-default-group").build()) {
            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.endpoints()).isEmpty());
        }
    }
}
