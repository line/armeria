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
package com.linecorp.armeria.server.nacos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.nacos.NacosTestBase;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;

@GenerateNativeImageTrace
class NacosUpdatingListenerTest extends NacosTestBase {

    private static final List<Server> servers = new ArrayList<>();
    private static volatile List<Endpoint> sampleEndpoints;

    @BeforeAll
    static void startServers() throws JsonProcessingException {

        await().pollInSameThread().pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThatCode(() -> {
                final List<Endpoint> endpoints = newSampleEndpoints();
                servers.clear();
                for (Endpoint endpoint : endpoints) {
                    final Server server = Server.builder()
                                                .http(endpoint.port())
                                                .service("/echo", new EchoService())
                                                .build();
                    final ServerListener listener =
                            NacosUpdatingListener
                                    .builder(nacosUri(), serviceName)
                                    .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                                    .endpoint(endpoint)
                                    .build();
                    server.addListener(listener);
                    server.start().join();
                    servers.add(server);
                }
                sampleEndpoints = endpoints;
            }).doesNotThrowAnyException();
        });
    }

    @AfterAll
    static void stopServers() throws Exception {
        servers.forEach(Server::close);
        servers.clear();
    }

    @Test
    void testBuild() {
        assertThat(NacosUpdatingListener.builder(nacosUri(), serviceName)
                                         .build()).isNotNull();
        assertThat(NacosUpdatingListener.builder(nacosUri(), serviceName)
                                         .build()).isNotNull();
    }

    @Test
    void testEndpointsCountOfListeningServiceWithAServerStopAndStart() {
        // Checks sample endpoints created when initialized.
        await().untilAsserted(() -> {
            assertThat(client().endpoints(serviceName, null, null, null, null, null)
                               .join()).hasSameSizeAs(sampleEndpoints);
        });

        // When we close one server then the listener deregister it automatically from nacos.
        servers.get(0).stop().join();

        await().untilAsserted(() -> {
            final List<Endpoint> results = client()
                    .endpoints(serviceName, null, null, null, null, null).join();
            assertThat(results).hasSize(sampleEndpoints.size() - 1);
        });

        // Endpoints increased after service restart.
        servers.get(0).start().join();

        await().untilAsserted(() -> {
            assertThat(client().endpoints(serviceName, null, null, null, null, null)
                               .join()).hasSameSizeAs(sampleEndpoints);
        });
    }

    @Test
    void testThatGroupNameIsSpecified() {
        final int port = unusedPorts(1)[0];
        final Endpoint endpoint = Endpoint.of("host.docker.internal", port).withWeight(1);

        final Server server = Server.builder()
                                    .http(port)
                                    .service("/echo", new EchoService())
                                    .build();
        final ServerListener listener =
                NacosUpdatingListener.builder(nacosUri(), "testThatGroupNameIsSpecified")
                                     .nacosApiVersion("v1")
                                     .authorization(NACOS_AUTH_SECRET, NACOS_AUTH_SECRET)
                                     .endpoint(endpoint)
                                     .groupName("groupName")
                                     .build();
        server.addListener(listener);
        server.start().join();
        await().untilAsserted(() -> {
            assertThat(client().endpoints("testThatGroupNameIsSpecified",
                                          null, "groupName", null,
                                          null, null).join())
                    .hasSize(1);
        });
        server.stop();
    }
}
