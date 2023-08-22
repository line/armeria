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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.consul.ConsulTestBase;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;

@GenerateNativeImageTrace
class ConsulUpdatingListenerTest extends ConsulTestBase {

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
                            ConsulUpdatingListener
                                    .builder(consulUri(), serviceName)
                                    .consulToken(CONSUL_TOKEN)
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
        assertThat(ConsulUpdatingListener.builder(consulUri(), serviceName)
                                         .build()).isNotNull();
        assertThat(ConsulUpdatingListener.builder(consulUri(), serviceName)
                                         .build()).isNotNull();
    }

    @Test
    void testThatDefaultCheckMethodIsHead() {
        final AtomicReference<Server> serverRef = new AtomicReference<>();
        await().pollInSameThread().pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThatCode(() -> {
                final int port = unusedPorts(1)[0];
                final Endpoint endpoint = Endpoint.of("host.docker.internal", port).withWeight(1);
                final Server server = Server.builder()
                                            .http(port)
                                            .service("/echo", new EchoService())
                                            .build();
                final ServerListener listener =
                        ConsulUpdatingListener.builder(consulUri(), "testThatDefaultCheckMethodIsHead")
                                              .consulApiVersion("v1")
                                              .consulToken(CONSUL_TOKEN)
                                              .endpoint(endpoint)
                                              .checkUri("http://" + endpoint.host() + ':' + endpoint.port() +
                                                        "/echo")
                                              .checkInterval(Duration.ofSeconds(1))
                                              .build();
                server.addListener(listener);
                server.start().join();
                serverRef.set(server);
            }).doesNotThrowAnyException();
        });
        await().untilAsserted(() -> {
            assertThat(client().healthyEndpoints("testThatDefaultCheckMethodIsHead").join().size())
                    .isEqualTo(1);
        });
        serverRef.get().stop();
    }

    @Test
    void testEndpointsCountOfListeningServiceWithAServerStopAndStart() {
        // Checks sample endpoints created when initialized.
        await().untilAsserted(() -> {
            assertThat(client().endpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints);
        });

        // When we close one server then the listener deregister it automatically from consul agent.
        servers.get(0).stop().join();

        await().untilAsserted(() -> {
            final List<Endpoint> results = client().endpoints(serviceName).join();
            assertThat(results).hasSize(sampleEndpoints.size() - 1);
        });

        // Endpoints increased after service restart.
        servers.get(0).start().join();

        await().untilAsserted(() -> {
            assertThat(client().endpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints);
        });
    }

    @Test
    void testHealthyServiceWithAdditionalCheckRule() {
        // Checks sample endpoints created when initialized.
        await().untilAsserted(() -> {
            assertThat(client().healthyEndpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints);
        });

        // Make a service to produce 503 error for checking by consul.
        final Endpoint firstEndpoint = sampleEndpoints.get(0).withHost("127.0.0.1");
        final BlockingWebClient webClient = BlockingWebClient.of(firstEndpoint.toUri(SessionProtocol.HTTP));
        webClient.post("echo", "503");

        // And then, consul marks the service to an unhealthy state.
        await().untilAsserted(() -> {
            assertThat(client().healthyEndpoints(serviceName).join())
                    .hasSize(sampleEndpoints.size() - 1);
        });

        // But, the size of endpoints is not changed.
        await().untilAsserted(() -> {
            assertThat(client().endpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints);
        });

        // Make a service to produce 200 OK for checking by consul.
        webClient.post("echo", "200");
        await().untilAsserted(() -> {
            assertThat(client().healthyEndpoints(serviceName).join()).hasSameSizeAs(sampleEndpoints);
        });
    }

    @Test
    void testThatTagsAreAdded() {
        final int port = unusedPorts(1)[0];
        final Endpoint endpoint = Endpoint.of("host.docker.internal", port).withWeight(1);

        final Server server = Server.builder()
                                    .http(port)
                                    .service("/echo", new EchoService())
                                    .build();
        final ServerListener listener =
                ConsulUpdatingListener.builder(consulUri(), "testThatTagsAreAdded")
                                      .consulApiVersion("v1")
                                      .consulToken(CONSUL_TOKEN)
                                      .endpoint(endpoint)
                                      .tags("production", "v1")
                                      .build();
        server.addListener(listener);
        server.start().join();
        await().untilAsserted(() -> {
            assertThat(client().healthyEndpoints("testThatTagsAreAdded", null,
                                                 "Service.Tags contains \"v1\"").join())
                    .hasSize(1);
        });
        server.stop();
    }
}
