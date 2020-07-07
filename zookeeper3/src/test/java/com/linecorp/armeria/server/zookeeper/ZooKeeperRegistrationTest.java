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
package com.linecorp.armeria.server.zookeeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.ServiceType;
import org.apache.curator.x.discovery.UriSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.zookeeper.ZooKeeperDiscoverySpec;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.zookeeper.ZooKeeperExtension;
import com.linecorp.armeria.common.zookeeper.ZooKeeperTestUtil;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;

import zookeeperjunit.CloseableZooKeeper;

class ZooKeeperRegistrationTest {

    private static final String Z_NODE = "/testEndPoints";
    private static final String CURATOR_X_SERVICE_NAME = "foo";
    private static final String CURATOR_X_ADDRESS = "foo.com";
    private static final int SESSION_TIMEOUT_MILLIS = 20000;
    private static final List<Endpoint> sampleEndpoints = ZooKeeperTestUtil.sampleEndpoints(3);

    @RegisterExtension
    static ZooKeeperExtension zkInstance = new ZooKeeperExtension();

    @Test
    void legacyZooKeeperRegistrationSpec() throws Throwable {
        final List<Server> servers = startServers(true);
        // all servers start and with znode created
        await().untilAsserted(() -> sampleEndpoints.forEach(
                endpoint -> zkInstance.assertExists(Z_NODE + '/' + endpoint.host() + '_' + endpoint.port())));

        try (CloseableZooKeeper zk = zkInstance.connection()) {
            for (Endpoint sampleEndpoint : sampleEndpoints) {
                assertThat(ZooKeeperDiscoverySpec.legacy().decode(zk.getData(
                        Z_NODE + '/' + sampleEndpoint.host() + '_' + sampleEndpoint.port()).get()))
                        .isEqualTo(sampleEndpoint);
            }
            validateOneNodeRemoved(servers, zk, true);
        }
        servers.forEach(s -> s.stop().join());
    }

    private static void validateOneNodeRemoved(
            List<Server> servers, CloseableZooKeeper zk, boolean endpointRegistrationSpec) throws Throwable {
        servers.get(0).stop().get();
        servers.remove(0);

        int removed = 0;
        int remaining = 0;

        for (int i = 0; i < sampleEndpoints.size(); i++) {
            final String key;
            if (endpointRegistrationSpec) {
                key = Z_NODE + '/' + sampleEndpoints.get(i).host() + '_' + sampleEndpoints.get(i).port();
            } else {
                key = Z_NODE + '/' + CURATOR_X_SERVICE_NAME + '/' + i;
            }
            if (zk.exists(key).get()) {
                remaining++;
            } else {
                removed++;
            }
        }

        assertThat(removed).isOne();
        assertThat(remaining).isEqualTo(sampleEndpoints.size() - 1);
    }

    private static List<Server> startServers(boolean legacySpec) throws Exception {
        final List<Server> servers = new ArrayList<>();
        for (int i = 0; i < sampleEndpoints.size(); i++) {
            final Server server = Server.builder()
                                        .http(sampleEndpoints.get(i).port())
                                        .service("/", (ctx, req) -> HttpResponse.of(200))
                                        .build();
            final ZooKeeperRegistrationSpec registrationSpec;
            if (legacySpec) {
                registrationSpec = ZooKeeperRegistrationSpec.legacy(sampleEndpoints.get(i));
            } else {
                registrationSpec = ZooKeeperRegistrationSpec.builderForCurator(CURATOR_X_SERVICE_NAME)
                                                            .serviceId(String.valueOf(i))
                                                            .serviceAddress(CURATOR_X_ADDRESS)
                                                            .build();
            }
            final ServerListener listener =
                    ZooKeeperUpdatingListener.builder(zkInstance.connectString(), Z_NODE, registrationSpec)
                                             .sessionTimeoutMillis(SESSION_TIMEOUT_MILLIS)
                                             .build();
            server.addListener(listener);

            // Work around sporadic 'address already in use' errors.
            await().pollInSameThread().pollInterval(Duration.ofSeconds(1)).untilAsserted(
                    () -> assertThatCode(() -> server.start().join()).doesNotThrowAnyException());

            servers.add(server);
        }
        return servers;
    }

    @Test
    void curatorRegistrationSpec() throws Throwable {
        final List<Server> servers = startServers(false);
        // all servers start and with znode created
        await().untilAsserted(() -> {
            for (int i = 0; i < 3; i++) {
                zkInstance.assertExists(Z_NODE + '/' + CURATOR_X_SERVICE_NAME + '/' + i);
            }
        });

        try (CloseableZooKeeper zk = zkInstance.connection()) {
            for (int i = 0; i < sampleEndpoints.size(); i++) {
                final CompletableFuture<ServiceInstance<?>> instanceCaptor = new CompletableFuture<>();
                final ZooKeeperDiscoverySpec discoverySpec =
                        ZooKeeperDiscoverySpec.builderForCurator(CURATOR_X_SERVICE_NAME)
                                              .converter(serviceInstance -> {
                                         instanceCaptor.complete(serviceInstance);
                                         return null;
                                     }).build();
                discoverySpec.decode(zk.getData(Z_NODE + '/' + CURATOR_X_SERVICE_NAME + '/' + i).get());
                final ServiceInstance<?> actual = instanceCaptor.join();
                final ServiceInstance<Object> expected = expectedInstance(servers, i);
                assertThat(actual).isEqualToIgnoringGivenFields(expected, "registrationTimeUTC");
            }
            validateOneNodeRemoved(servers, zk, false);
        }
        servers.forEach(s -> s.stop().join());
    }

    private static ServiceInstance<Object> expectedInstance(List<Server> servers, int index) {
        return new ServiceInstance<>(
                CURATOR_X_SERVICE_NAME, String.valueOf(index), CURATOR_X_ADDRESS,
                servers.get(index).activeLocalPort(), null, null,
                0, ServiceType.DYNAMIC, new UriSpec("{scheme}://{address}:{port}"), true);
    }
}
