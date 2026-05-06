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
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterRoot;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.MissingXdsResourceException;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsType;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class DeltaXdsResourceWatcherTest {

    private static final String GROUP = "key";
    private static final String CLUSTER_NAME = "cluster1";
    private static final String CLUSTER_NAME_2 = "cluster2";
    private static final String LISTENER_NAME = "listener1";
    private static final String ROUTE_NAME = "route1";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";

    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    @Order(0)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
            sb.http(0);
        }

        @Override
        protected boolean runForEachTest() {
            return true;
        }
    };

    @RegisterExtension
    @Order(1)
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void listenerAndRouteLifecycle() {
        // Seed an empty snapshot so the control plane accepts delta subscriptions immediately.
        cache.setSnapshot(GROUP, Snapshot.create(
                ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), "0"));

        final Bootstrap bootstrap =
                bootstrapYaml(BOOTSTRAP_CLUSTER_NAME,
                              server.httpSocketAddress().getHostString(),
                              server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(LISTENER_NAME)) {

            final List<ListenerSnapshot> snapshots = new CopyOnWriteArrayList<>();
            final List<Throwable> errors = new CopyOnWriteArrayList<>();

            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
                if (t != null) {
                    errors.add(t);
                }
            });

            // Step 1: Add listener1 → route1 → cluster1 (STATIC cluster with inline endpoints)
            cache.setSnapshot(GROUP, Snapshot.create(
                    ImmutableList.of(staticClusterYaml(CLUSTER_NAME)),
                    ImmutableList.of(),
                    ImmutableList.of(listenerYaml(LISTENER_NAME, ROUTE_NAME)),
                    ImmutableList.of(routeYaml(ROUTE_NAME, CLUSTER_NAME)),
                    ImmutableList.of(),
                    "1"));
            await().untilAsserted(() -> {
                assertThat(snapshots).hasSize(1);
                final ListenerSnapshot s = snapshots.get(0);
                assertThat(s.xdsResource().resource().getName()).isEqualTo(LISTENER_NAME);
                final RouteConfiguration routeConfig = s.routeSnapshot().xdsResource().resource();
                assertThat(routeConfig.getName()).isEqualTo(ROUTE_NAME);
                assertThat(routeConfig.getVirtualHosts(0).getRoutes(0).getRoute().getCluster())
                        .isEqualTo(CLUSTER_NAME);
            });

            // Step 2: Update route to reference cluster2
            cache.setSnapshot(GROUP, Snapshot.create(
                    ImmutableList.of(staticClusterYaml(CLUSTER_NAME_2)),
                    ImmutableList.of(),
                    ImmutableList.of(listenerYaml(LISTENER_NAME, ROUTE_NAME)),
                    ImmutableList.of(routeYaml(ROUTE_NAME, CLUSTER_NAME_2)),
                    ImmutableList.of(),
                    "2"));
            await().untilAsserted(() -> {
                assertThat(snapshots).hasSize(2);
                final RouteConfiguration routeConfig =
                        snapshots.get(1).routeSnapshot().xdsResource().resource();
                assertThat(routeConfig.getVirtualHosts(0).getRoutes(0).getRoute().getCluster())
                        .isEqualTo(CLUSTER_NAME_2);
            });

            // Step 3: Delete listener — errors queue gets a throwable; no new snapshot
            cache.setSnapshot(GROUP, Snapshot.create(
                    ImmutableList.of(staticClusterYaml(CLUSTER_NAME)),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(routeYaml(ROUTE_NAME, CLUSTER_NAME)),
                    ImmutableList.of(),
                    "3"));
            await().untilAsserted(() -> assertThat(errors).anyMatch(error -> {
                if (!(error instanceof MissingXdsResourceException)) {
                    return false;
                }
                final MissingXdsResourceException exception = (MissingXdsResourceException) error;
                return exception.type() == XdsType.LISTENER &&
                       exception.name().equals(LISTENER_NAME);
            }));
            final int sizeAfterRemoval = snapshots.size();

            // Step 4: Re-add listener → route1 → cluster1
            cache.setSnapshot(GROUP, Snapshot.create(
                    ImmutableList.of(staticClusterYaml(CLUSTER_NAME)),
                    ImmutableList.of(),
                    ImmutableList.of(listenerYaml(LISTENER_NAME, ROUTE_NAME)),
                    ImmutableList.of(routeYaml(ROUTE_NAME, CLUSTER_NAME)),
                    ImmutableList.of(),
                    "4"));
            await().untilAsserted(() -> {
                assertThat(snapshots.size()).isGreaterThan(sizeAfterRemoval);
                final ListenerSnapshot s = snapshots.get(snapshots.size() - 1);
                assertThat(s.xdsResource().resource().getName()).isEqualTo(LISTENER_NAME);
                assertThat(s.routeSnapshot().xdsResource().resource()
                            .getVirtualHosts(0).getRoutes(0).getRoute().getCluster())
                        .isEqualTo(CLUSTER_NAME);
            });
        }
    }

    @Test
    void clusterAndEndpointLifecycle() {
        // Seed an empty snapshot so the control plane accepts delta subscriptions immediately.
        cache.setSnapshot(GROUP, Snapshot.create(
                ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), "0"));

        final Bootstrap bootstrap =
                bootstrapYaml(BOOTSTRAP_CLUSTER_NAME,
                              server.httpSocketAddress().getHostString(),
                              server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(CLUSTER_NAME)) {

            final List<ClusterSnapshot> snapshots = new CopyOnWriteArrayList<>();
            final List<Throwable> errors = new CopyOnWriteArrayList<>();

            clusterRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
                if (t != null) {
                    errors.add(t);
                }
            });

            // Step 1: Add cluster1 + endpoint at port 1234
            cache.setSnapshot(GROUP, Snapshot.create(
                    ImmutableList.of(clusterYaml(CLUSTER_NAME)),
                    ImmutableList.of(endpointYaml(CLUSTER_NAME, "127.0.0.1", 1234)),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    "1"));
            await().untilAsserted(() -> {
                assertThat(snapshots).hasSize(1);
                final ClusterLoadAssignment cla =
                        snapshots.get(0).endpointSnapshot().xdsResource().resource();
                assertThat(cla.getEndpoints(0).getLbEndpoints(0)
                              .getEndpoint().getAddress().getSocketAddress().getPortValue())
                        .isEqualTo(1234);
            });

            // Step 2: Update endpoint port to 5678
            cache.setSnapshot(GROUP, Snapshot.create(
                    ImmutableList.of(clusterYaml(CLUSTER_NAME)),
                    ImmutableList.of(endpointYaml(CLUSTER_NAME, "127.0.0.1", 5678)),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    "2"));
            await().untilAsserted(() -> {
                assertThat(snapshots).hasSize(2);
                final ClusterLoadAssignment cla =
                        snapshots.get(1).endpointSnapshot().xdsResource().resource();
                assertThat(cla.getEndpoints(0).getLbEndpoints(0)
                              .getEndpoint().getAddress().getSocketAddress().getPortValue())
                        .isEqualTo(5678);
            });

            // Step 3: Add a second lb_endpoint (ports 5678, 9090)
            cache.setSnapshot(GROUP, Snapshot.create(
                    ImmutableList.of(clusterYaml(CLUSTER_NAME)),
                    ImmutableList.of(endpointYamlMulti(CLUSTER_NAME, "127.0.0.1", 5678, 9090)),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    "3"));
            await().untilAsserted(() -> {
                assertThat(snapshots).hasSize(3);
                final ClusterLoadAssignment cla =
                        snapshots.get(2).endpointSnapshot().xdsResource().resource();
                assertThat(cla.getEndpoints(0).getLbEndpointsList()).hasSize(2);
            });

            // Step 4: Delete cluster — both CLUSTER and ENDPOINT deletions fire,
            // so errors gets at least one throwable.
            cache.setSnapshot(GROUP, Snapshot.create(
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    "4"));
            await().untilAsserted(() -> assertThat(errors).isNotEmpty());
        }
    }

    private static Cluster staticClusterYaml(String name) {
        //language=YAML
        final String yaml = """
                name: %s
                type: STATIC
                load_assignment:
                  cluster_name: %s
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 1
                """.formatted(name, name);
        return XdsResourceReader.fromYaml(yaml, Cluster.class);
    }

    private static Cluster clusterYaml(String name) {
        //language=YAML
        final String yaml = """
                name: %s
                type: EDS
                connect_timeout: 1s
                eds_cluster_config:
                  eds_config:
                    ads: {}
                """.formatted(name);
        return XdsResourceReader.fromYaml(yaml, Cluster.class);
    }

    private static ClusterLoadAssignment endpointYaml(String clusterName, String address, int port) {
        //language=YAML
        final String yaml = """
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: %s
                          port_value: %s
                """.formatted(clusterName, address, port);
        return XdsResourceReader.fromYaml(yaml, ClusterLoadAssignment.class);
    }

    private static ClusterLoadAssignment endpointYamlMulti(String clusterName, String address,
                                                            int port1, int port2) {
        //language=YAML
        final String yaml = """
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: %s
                          port_value: %s
                  - endpoint:
                      address:
                        socket_address:
                          address: %s
                          port_value: %s
                """.formatted(clusterName, address, port1, address, port2);
        return XdsResourceReader.fromYaml(yaml, ClusterLoadAssignment.class);
    }

    private static Listener listenerYaml(String name, String routeName) {
        //language=YAML
        final String yaml = """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: http
                    rds:
                      route_config_name: %s
                      config_source:
                        ads: {}
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(name, routeName);
        return XdsResourceReader.fromYaml(yaml, Listener.class);
    }

    private static RouteConfiguration routeYaml(String name, String clusterName) {
        //language=YAML
        final String yaml = """
                name: %s
                virtual_hosts:
                - name: local_service1
                  domains: [ "*" ]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: %s
                """.formatted(name, clusterName);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static Bootstrap bootstrapYaml(String clusterName, String address, int port) {
        //language=YAML
        final String yaml = """
                dynamic_resources:
                  ads_config:
                    api_type: AGGREGATED_DELTA_GRPC
                    grpc_services:
                    - envoy_grpc:
                        cluster_name: %s
                  cds_config:
                    ads: {}
                  lds_config:
                    ads: {}
                static_resources:
                  clusters:
                  - name: %s
                    type: STATIC
                    load_assignment:
                      cluster_name: %s
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: %s
                                port_value: %s
                """.formatted(clusterName, clusterName, clusterName, address, port);
        return XdsResourceReader.fromYaml(yaml, Bootstrap.class);
    }
}
