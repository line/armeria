/*
 * Copyright 2026 LY Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsResourceReader;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * Verifies that the xds module works when the vanilla java-control-plane api protos
 * are used instead of Armeria's xds-api (which adds the {@code custom_config_source}
 * extension field).
 */
class ControlPlaneApiRegressionTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
        }
    };

    @Test
    void customConfigSourceNotPresent() {
        // Verify we're using the vanilla java-control-plane protos, not Armeria's xds-api.
        assertThatThrownBy(() -> ConfigSource.class.getMethod("hasCustomConfigSource"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void basicAdsClusterDiscovery() {
        //language=JSON
        final String clusterJson =
                """
                {
                  "name": "my-cluster",
                  "type": "STATIC",
                  "loadAssignment": {
                    "clusterName": "my-cluster",
                    "endpoints": [
                      {
                        "lbEndpoints": [
                          {
                            "endpoint": {
                              "address": {
                                "socketAddress": {
                                  "address": "127.0.0.1",
                                  "portValue": 8080
                                }
                              }
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        final Cluster cluster = XdsResourceReader.from(clusterJson, Cluster.class);
        final ClusterLoadAssignment loadAssignment = cluster.getLoadAssignment();
        cache.setSnapshot(GROUP, Snapshot.create(
                ImmutableList.of(cluster), ImmutableList.of(loadAssignment),
                ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), "1"));

        final Bootstrap bootstrap = XdsResourceReader.from(
                bootstrapYaml(server.httpPort()), Bootstrap.class);
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            xdsBootstrap.clusterRoot("my-cluster")
                        .addSnapshotWatcher((snapshot, t) -> {
                            if (snapshot != null) {
                                snapshotRef.set(snapshot);
                            }
                        });

            await().untilAsserted(() -> {
                assertThat(snapshotRef.get()).isNotNull();
                assertThat(snapshotRef.get().xdsResource().resource().getName())
                        .isEqualTo("my-cluster");
            });
        }
    }

    @Test
    void edsClusterWithSelfConfigSource() {
        // An EDS cluster with 'self' config source triggers hasExplicitConfigSource()
        // on a ConfigSource where only 'self' is set. This evaluates all conditions
        // including hasCustomConfigSource(), exercising the missing-method code path.
        //language=JSON
        final String clusterJson =
                """
                {
                  "name": "eds-cluster",
                  "type": "EDS",
                  "edsClusterConfig": {
                    "edsConfig": {
                      "self": {}
                    },
                    "serviceName": "eds-cluster"
                  }
                }
                """;

        //language=JSON
        final String endpointsJson =
                """
                {
                  "clusterName": "eds-cluster",
                  "endpoints": [
                    {
                      "lbEndpoints": [
                        {
                          "endpoint": {
                            "address": {
                              "socketAddress": {
                                "address": "127.0.0.1",
                                "portValue": 8080
                              }
                            }
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

        final Cluster cluster = XdsResourceReader.from(clusterJson, Cluster.class);
        final ClusterLoadAssignment loadAssignment =
                XdsResourceReader.from(endpointsJson, ClusterLoadAssignment.class);
        cache.setSnapshot(GROUP, Snapshot.create(
                ImmutableList.of(cluster), ImmutableList.of(loadAssignment),
                ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), "1"));

        final Bootstrap bootstrap = XdsResourceReader.from(
                bootstrapYaml(server.httpPort()), Bootstrap.class);
        final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            xdsBootstrap.clusterRoot("eds-cluster")
                        .addSnapshotWatcher((snapshot, t) -> {
                            if (snapshot != null) {
                                snapshotRef.set(snapshot);
                            }
                        });

            await().untilAsserted(() -> {
                assertThat(snapshotRef.get()).isNotNull();
                assertThat(snapshotRef.get().xdsResource().resource().getName())
                        .isEqualTo("eds-cluster");
                assertThat(snapshotRef.get().endpointSnapshot()).isNotNull();
            });
        }
    }

    private static String bootstrapYaml(int port) {
        //language=YAML
        return """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: bootstrap-cluster
                  cds_config:
                    ads: {}
                static_resources:
                  clusters:
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
                """.formatted(port);
    }
}
