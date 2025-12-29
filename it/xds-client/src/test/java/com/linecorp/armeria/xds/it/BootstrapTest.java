/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

class BootstrapTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final AtomicLong version = new AtomicLong();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    @Test
    void secondaryInitTest() {
        //language=YAML
        final String bootstrapYaml =
                """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: bootstrap-cluster
                static_resources:
                  clusters:
                    - name: my-cluster
                      type: EDS
                      eds_cluster_config:
                        eds_config:
                          ads: {}
                    - name: bootstrap-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: bootstrap-cluster
                        endpoints:
                        - lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: %s
                """;

        //language=YAML
        final String endpointYaml =
                """
                cluster_name: my-cluster
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: 127.0.0.1
                          port_value: 1234
                """;

        final String bootstrapStr = bootstrapYaml.formatted(server.httpPort());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
        final ClusterLoadAssignment endpoint = XdsResourceReader.fromYaml(endpointYaml,
                                                                          ClusterLoadAssignment.class);
        version.incrementAndGet();
        final Snapshot snapshot = Snapshot.create(ImmutableList.of(), ImmutableList.of(endpoint),
                                                  ImmutableList.of(), ImmutableList.of(),
                                                  ImmutableList.of(), version.toString());
        cache.setSnapshot(GROUP, snapshot);

        final AtomicReference<Object> objRef = new AtomicReference<>();
        final SnapshotWatcher<Object> watcher = new SnapshotWatcher<>() {
            @Override
            public void onUpdate(@Nullable Object snapshot, @Nullable Throwable t) {
                if (snapshot != null) {
                    objRef.set(snapshot);
                }
            }
        };
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .defaultSnapshotWatcher(watcher)
                                                     .build()) {
            xdsBootstrap.clusterRoot("my-cluster");
            await().untilAsserted(() -> {
                assertThat(objRef.get()).isNotNull();
                final ClusterSnapshot clusterSnapshot = (ClusterSnapshot) objRef.get();
                assertThat(clusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(endpoint);
            });
        }
    }
}
