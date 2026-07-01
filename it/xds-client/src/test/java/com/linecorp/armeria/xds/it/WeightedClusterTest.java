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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Struct;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteCluster;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.WeightedClusterSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class WeightedClusterTest {

    private static final String GROUP = "key";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final AtomicLong version = new AtomicLong();

    @RegisterExtension
    @Order(0)
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
            sb.http(0);
        }
    };

    @RegisterExtension
    @Order(1)
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @BeforeEach
    void beforeEach() {
        cache.setSnapshot(GROUP,
                          Snapshot.create(ImmutableList.of(), ImmutableList.of(),
                                         ImmutableList.of(), ImmutableList.of(),
                                         ImmutableList.of(),
                                         String.valueOf(version.incrementAndGet())));
    }

    @Test
    void weightedClustersSnapshotStructure() throws Exception {
        final Listener listener = listener("listener_0", "route_0");
        final RouteConfiguration routeConfig = weightedRouteConfig("route_0", "cluster-a", 80, "cluster-b", 20);
        final Cluster clusterA = cluster("cluster-a");
        final Cluster clusterB = cluster("cluster-b");
        final ClusterLoadAssignment endpointA = endpoint("cluster-a", "127.0.0.1", 1234);
        final ClusterLoadAssignment endpointB = endpoint("cluster-b", "127.0.0.1", 5678);

        cache.setSnapshot(GROUP,
                          Snapshot.create(ImmutableList.of(clusterA, clusterB),
                                         ImmutableList.of(endpointA, endpointB),
                                         ImmutableList.of(listener),
                                         ImmutableList.of(routeConfig),
                                         ImmutableList.of(), nextVersion()));

        final Bootstrap bootstrap = bootstrap(server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener_0")) {
            final RecordingWatcher watcher = new RecordingWatcher();
            listenerRoot.addSnapshotWatcher(watcher);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(watcher.lastSnapshot()).isNotNull();
            });

            final ListenerSnapshot listenerSnapshot = watcher.lastSnapshot();
            final List<RouteEntry> routeEntries =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0).routeEntries();
            assertThat(routeEntries).hasSize(1);

            final RouteEntry routeEntry = routeEntries.get(0);
            // Single cluster should be null for weighted routes
            assertThat(routeEntry.clusterSnapshot()).isNull();
            // Weighted cluster entries should be present
            assertThat(routeEntry.weightedClusters()).isNotNull();
            assertThat(routeEntry.weightedClusters()).hasSize(2);

            final List<WeightedClusterSnapshot> entries = routeEntry.weightedClusters();
            assertThat(entries.get(0).clusterSnapshot().xdsResource().name()).isEqualTo("cluster-a");
            assertThat(entries.get(0).weight()).isEqualTo(80);
            assertThat(entries.get(1).clusterSnapshot().xdsResource().name()).isEqualTo("cluster-b");
            assertThat(entries.get(1).weight()).isEqualTo(20);

            // resolve should always return a non-null target
            assertThat(routeEntry.resolve()).isNotNull();
        }
    }

    @Test
    void weightDistribution() throws Exception {
        final Listener listener = listener("listener_0", "route_0");
        final RouteConfiguration routeConfig = weightedRouteConfig("route_0", "cluster-a", 3, "cluster-b", 1);
        final Cluster clusterA = cluster("cluster-a");
        final Cluster clusterB = cluster("cluster-b");
        final ClusterLoadAssignment endpointA = endpoint("cluster-a", "127.0.0.1", 1234);
        final ClusterLoadAssignment endpointB = endpoint("cluster-b", "127.0.0.1", 5678);

        cache.setSnapshot(GROUP,
                          Snapshot.create(ImmutableList.of(clusterA, clusterB),
                                         ImmutableList.of(endpointA, endpointB),
                                         ImmutableList.of(listener),
                                         ImmutableList.of(routeConfig),
                                         ImmutableList.of(), nextVersion()));

        final Bootstrap bootstrap = bootstrap(server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener_0")) {
            final RecordingWatcher watcher = new RecordingWatcher();
            listenerRoot.addSnapshotWatcher(watcher);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(watcher.lastSnapshot()).isNotNull();
            });

            final RouteEntry routeEntry =
                    watcher.lastSnapshot().routeSnapshot().virtualHostSnapshots().get(0)
                           .routeEntries().get(0);

            // Weighted round-robin is deterministic. Over one full cycle (totalWeight = 4),
            // cluster-a (weight 3) is picked 3 times and cluster-b (weight 1) is picked once.
            int countA = 0;
            int countB = 0;
            for (int i = 0; i < 4; i++) {
                final RouteCluster selected = routeEntry.resolve();
                assertThat(selected).isNotNull();
                if ("cluster-a".equals(selected.clusterSnapshot().xdsResource().name())) {
                    countA++;
                } else {
                    countB++;
                }
            }
            assertThat(countA).isEqualTo(3);
            assertThat(countB).isEqualTo(1);
        }
    }

    @Test
    void dynamicUpdate() throws Exception {
        final Listener listener = listener("listener_0", "route_0");
        final RouteConfiguration routeConfig = weightedRouteConfig("route_0", "cluster-a", 50, "cluster-b", 50);
        final Cluster clusterA = cluster("cluster-a");
        final Cluster clusterB = cluster("cluster-b");
        final ClusterLoadAssignment endpointA = endpoint("cluster-a", "127.0.0.1", 1234);
        final ClusterLoadAssignment endpointB = endpoint("cluster-b", "127.0.0.1", 5678);

        cache.setSnapshot(GROUP,
                          Snapshot.create(ImmutableList.of(clusterA, clusterB),
                                         ImmutableList.of(endpointA, endpointB),
                                         ImmutableList.of(listener),
                                         ImmutableList.of(routeConfig),
                                         ImmutableList.of(), nextVersion()));

        final Bootstrap bootstrap = bootstrap(server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener_0")) {
            final RecordingWatcher watcher = new RecordingWatcher();
            listenerRoot.addSnapshotWatcher(watcher);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(watcher.lastSnapshot()).isNotNull();
                assertThat(watcher.lastSnapshot().routeSnapshot().virtualHostSnapshots().get(0)
                                  .routeEntries().get(0).weightedClusters()).hasSize(2);
            });

            // Update endpoints for cluster-a
            final ClusterLoadAssignment updatedEndpointA = endpoint("cluster-a", "127.0.0.2", 9999);
            cache.setSnapshot(GROUP,
                              Snapshot.create(ImmutableList.of(clusterA, clusterB),
                                             ImmutableList.of(updatedEndpointA, endpointB),
                                             ImmutableList.of(listener),
                                             ImmutableList.of(routeConfig),
                                             ImmutableList.of(), nextVersion()));

            // Wait for re-emission with updated endpoint
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                final ListenerSnapshot latest = watcher.lastSnapshot();
                assertThat(latest).isNotNull();
                final RouteEntry re = latest.routeSnapshot().virtualHostSnapshots().get(0)
                                            .routeEntries().get(0);
                assertThat(re.weightedClusters()).isNotNull();
                final ClusterSnapshot csA = re.weightedClusters().get(0).clusterSnapshot();
                assertThat(csA.endpointSnapshot().xdsResource().resource()).isEqualTo(updatedEndpointA);
            });
        }
    }

    @Test
    void defaultWeight() throws Exception {
        // Clusters without explicit weight default to 1
        final RouteConfiguration routeConfig
                = weightedRouteConfigNoWeights("route_0", "cluster-a", "cluster-b");
        final Listener listener = listener("listener_0", "route_0");
        final Cluster clusterA = cluster("cluster-a");
        final Cluster clusterB = cluster("cluster-b");
        final ClusterLoadAssignment endpointA = endpoint("cluster-a", "127.0.0.1", 1234);
        final ClusterLoadAssignment endpointB = endpoint("cluster-b", "127.0.0.1", 5678);

        cache.setSnapshot(GROUP,
                          Snapshot.create(ImmutableList.of(clusterA, clusterB),
                                         ImmutableList.of(endpointA, endpointB),
                                         ImmutableList.of(listener),
                                         ImmutableList.of(routeConfig),
                                         ImmutableList.of(), nextVersion()));

        final Bootstrap bootstrap = bootstrap(server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener_0")) {
            final RecordingWatcher watcher = new RecordingWatcher();
            listenerRoot.addSnapshotWatcher(watcher);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(watcher.lastSnapshot()).isNotNull();
            });

            final RouteEntry routeEntry =
                    watcher.lastSnapshot().routeSnapshot().virtualHostSnapshots().get(0)
                           .routeEntries().get(0);

            assertThat(routeEntry.weightedClusters()).hasSize(2);
            // Both should default to weight 1
            assertThat(routeEntry.weightedClusters().get(0).weight()).isEqualTo(1);
            assertThat(routeEntry.weightedClusters().get(1).weight()).isEqualTo(1);

            // Equal weights: round-robin alternates between the two clusters
            final RouteCluster first = routeEntry.resolve();
            final RouteCluster second = routeEntry.resolve();
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(first.clusterSnapshot().xdsResource().name())
                    .isNotEqualTo(second.clusterSnapshot().xdsResource().name());
        }
    }

    @Test
    void metadataMatchMerging() throws Exception {
        // Route-level metadata_match: envoy.lb: {version: "v1", stage: "prod"}
        // cluster-a metadata_match:   envoy.lb: {stage: "canary"}         -> overrides stage
        // cluster-b metadata_match:   (none)                              -> inherits route-level
        final Listener listener = listener("listener_0", "route_0");
        final RouteConfiguration routeConfig = weightedRouteConfigWithMetadata("route_0");
        final Cluster clusterA = cluster("cluster-a");
        final Cluster clusterB = cluster("cluster-b");
        final ClusterLoadAssignment endpointA = endpoint("cluster-a", "127.0.0.1", 1234);
        final ClusterLoadAssignment endpointB = endpoint("cluster-b", "127.0.0.1", 5678);

        cache.setSnapshot(GROUP,
                          Snapshot.create(ImmutableList.of(clusterA, clusterB),
                                         ImmutableList.of(endpointA, endpointB),
                                         ImmutableList.of(listener),
                                         ImmutableList.of(routeConfig),
                                         ImmutableList.of(), nextVersion()));

        final Bootstrap bootstrap = bootstrap(server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener_0")) {
            final RecordingWatcher watcher = new RecordingWatcher();
            listenerRoot.addSnapshotWatcher(watcher);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(watcher.lastSnapshot()).isNotNull();
            });

            final RouteEntry routeEntry =
                    watcher.lastSnapshot().routeSnapshot().virtualHostSnapshots().get(0)
                           .routeEntries().get(0);

            assertThat(routeEntry.weightedClusters()).hasSize(2);

            // cluster-a: route metadata merged with cluster metadata
            // stage should be overridden to "canary", version should be preserved from route
            final WeightedClusterSnapshot entryA = routeEntry.weightedClusters().get(0);
            assertThat(entryA.clusterSnapshot().xdsResource().name()).isEqualTo("cluster-a");
            final Metadata metadataA = entryA.metadataMatch();
            final Struct envoyLbA = metadataA.getFilterMetadataOrThrow("envoy.lb");
            assertThat(envoyLbA.getFieldsOrThrow("version").getStringValue()).isEqualTo("v1");
            assertThat(envoyLbA.getFieldsOrThrow("stage").getStringValue()).isEqualTo("canary");

            // cluster-b: no cluster-level metadata, should inherit route-level metadata as-is
            final WeightedClusterSnapshot entryB = routeEntry.weightedClusters().get(1);
            assertThat(entryB.clusterSnapshot().xdsResource().name()).isEqualTo("cluster-b");
            final Metadata metadataB = entryB.metadataMatch();
            final Struct envoyLbB = metadataB.getFilterMetadataOrThrow("envoy.lb");
            assertThat(envoyLbB.getFieldsOrThrow("version").getStringValue()).isEqualTo("v1");
            assertThat(envoyLbB.getFieldsOrThrow("stage").getStringValue()).isEqualTo("prod");
        }
    }

    @Test
    void metadataMatchMergingMultipleFilterKeys() throws Exception {
        // Route-level metadata_match: envoy.lb: {version: "v1"}, custom.filter: {key1: "a"}
        // cluster-a metadata_match:   custom.filter: {key2: "b"}
        // Merged cluster-a: envoy.lb: {version: "v1"}, custom.filter: {key1: "a", key2: "b"}
        final Listener listener = listener("listener_0", "route_0");
        final RouteConfiguration routeConfig =
                weightedRouteConfigWithMultipleFilterKeys("route_0");
        final Cluster clusterA = cluster("cluster-a");
        final ClusterLoadAssignment endpointA = endpoint("cluster-a", "127.0.0.1", 1234);

        cache.setSnapshot(GROUP,
                          Snapshot.create(ImmutableList.of(clusterA),
                                         ImmutableList.of(endpointA),
                                         ImmutableList.of(listener),
                                         ImmutableList.of(routeConfig),
                                         ImmutableList.of(), nextVersion()));

        final Bootstrap bootstrap = bootstrap(server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener_0")) {
            final RecordingWatcher watcher = new RecordingWatcher();
            listenerRoot.addSnapshotWatcher(watcher);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(watcher.lastSnapshot()).isNotNull();
            });

            final RouteEntry routeEntry =
                    watcher.lastSnapshot().routeSnapshot().virtualHostSnapshots().get(0)
                           .routeEntries().get(0);

            final WeightedClusterSnapshot entry = routeEntry.weightedClusters().get(0);
            final Metadata metadata = entry.metadataMatch();

            // envoy.lb from route-level preserved entirely
            final Struct envoyLb = metadata.getFilterMetadataOrThrow("envoy.lb");
            assertThat(envoyLb.getFieldsOrThrow("version").getStringValue()).isEqualTo("v1");

            // custom.filter merged: key1 from route, key2 from cluster
            final Struct customFilter = metadata.getFilterMetadataOrThrow("custom.filter");
            assertThat(customFilter.getFieldsOrThrow("key1").getStringValue()).isEqualTo("a");
            assertThat(customFilter.getFieldsOrThrow("key2").getStringValue()).isEqualTo("b");
        }
    }

    @Test
    void zeroWeightIsRejected() throws Exception {
        final Listener listener = listener("listener_0", "route_0");
        final RouteConfiguration routeConfig =
                weightedRouteConfigWithZeroWeight("route_0", "cluster-a", "cluster-b");
        final Cluster clusterA = cluster("cluster-a");
        final Cluster clusterB = cluster("cluster-b");
        final ClusterLoadAssignment endpointA = endpoint("cluster-a", "127.0.0.1", 1234);
        final ClusterLoadAssignment endpointB = endpoint("cluster-b", "127.0.0.1", 5678);

        cache.setSnapshot(GROUP,
                          Snapshot.create(ImmutableList.of(clusterA, clusterB),
                                         ImmutableList.of(endpointA, endpointB),
                                         ImmutableList.of(listener),
                                         ImmutableList.of(routeConfig),
                                         ImmutableList.of(), nextVersion()));

        final Bootstrap bootstrap = bootstrap(server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener_0")) {
            final RecordingWatcher watcher = new RecordingWatcher();
            listenerRoot.addSnapshotWatcher(watcher);

            // The zero-weight should cause an error during route stream processing
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(watcher.errors()).isNotEmpty();
                assertThat(watcher.errors().get(0))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("weight of 0");
            });

            // No successful snapshot should be emitted
            assertThat(watcher.lastSnapshot()).isNull();
        }
    }

    @Test
    void singleClusterUnchanged() throws Exception {
        final Listener listener = listener("listener_0", "route_0");
        final RouteConfiguration routeConfig = singleClusterRouteConfig("route_0", "cluster-single");
        final Cluster cluster = cluster("cluster-single");
        final ClusterLoadAssignment ep = endpoint("cluster-single", "127.0.0.1", 1234);

        cache.setSnapshot(GROUP,
                          Snapshot.create(ImmutableList.of(cluster),
                                         ImmutableList.of(ep),
                                         ImmutableList.of(listener),
                                         ImmutableList.of(routeConfig),
                                         ImmutableList.of(), nextVersion()));

        final Bootstrap bootstrap = bootstrap(server.httpPort());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener_0")) {
            final RecordingWatcher watcher = new RecordingWatcher();
            listenerRoot.addSnapshotWatcher(watcher);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(watcher.lastSnapshot()).isNotNull();
            });

            final RouteEntry routeEntry =
                    watcher.lastSnapshot().routeSnapshot().virtualHostSnapshots().get(0)
                           .routeEntries().get(0);

            // Single cluster route: clusterSnapshot non-null, weighted entries null
            assertThat(routeEntry.clusterSnapshot()).isNotNull();
            assertThat(routeEntry.clusterSnapshot().xdsResource().name()).isEqualTo("cluster-single");
            assertThat(routeEntry.weightedClusters()).isNull();

            // resolve returns the same single cluster
            assertThat(routeEntry.resolve()).isNotNull();
            assertThat(routeEntry.resolve().clusterSnapshot()).isEqualTo(routeEntry.clusterSnapshot());
        }
    }

    private static String nextVersion() {
        return String.valueOf(version.incrementAndGet());
    }

    private static Bootstrap bootstrap(int port) {
        final String yaml = """
                static_resources:
                  clusters:
                    - name: %s
                      connect_timeout: 5s
                      type: STATIC
                      load_assignment:
                        cluster_name: %s
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: 127.0.0.1
                                      port_value: %s
                dynamic_resources:
                  ads_config:
                    api_type: AGGREGATED_GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: %s
                  lds_config:
                    ads: {}
                  cds_config:
                    ads: {}
                """.formatted(BOOTSTRAP_CLUSTER_NAME, BOOTSTRAP_CLUSTER_NAME, port, BOOTSTRAP_CLUSTER_NAME);
        return XdsResourceReader.fromYaml(yaml, Bootstrap.class);
    }

    private static Listener listener(String name, String routeName) {
        final String yaml = """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                    codec_type: AUTO
                    stat_prefix: ingress_http
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

    private static RouteConfiguration weightedRouteConfig(String name,
                                                          String cluster1, int weight1,
                                                          String cluster2, int weight2) {
        final String yaml = """
                name: %s
                virtual_hosts:
                  - name: weighted-vhost
                    domains: ["*"]
                    routes:
                      - match:
                          prefix: "/"
                        route:
                          weighted_clusters:
                            clusters:
                              - name: %s
                                weight: %d
                              - name: %s
                                weight: %d
                """.formatted(name, cluster1, weight1, cluster2, weight2);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static RouteConfiguration weightedRouteConfigNoWeights(String name,
                                                                   String cluster1, String cluster2) {
        final String yaml = """
                name: %s
                virtual_hosts:
                  - name: weighted-vhost
                    domains: ["*"]
                    routes:
                      - match:
                          prefix: "/"
                        route:
                          weighted_clusters:
                            clusters:
                              - name: %s
                              - name: %s
                """.formatted(name, cluster1, cluster2);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static RouteConfiguration singleClusterRouteConfig(String name, String clusterName) {
        final String yaml = """
                name: %s
                virtual_hosts:
                  - name: single-vhost
                    domains: ["*"]
                    routes:
                      - match:
                          prefix: "/"
                        route:
                          cluster: %s
                """.formatted(name, clusterName);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static Cluster cluster(String name) {
        final String yaml = """
                name: %s
                connect_timeout: 5s
                type: EDS
                eds_cluster_config:
                  eds_config:
                    ads: {}
                  service_name: %s
                """.formatted(name, name);
        return XdsResourceReader.fromYaml(yaml, Cluster.class);
    }

    private static ClusterLoadAssignment endpoint(String clusterName, String address, int port) {
        final String yaml = """
                cluster_name: %s
                endpoints:
                  - lb_endpoints:
                      - endpoint:
                          address:
                            socket_address:
                              address: %s
                              port_value: %d
                """.formatted(clusterName, address, port);
        return XdsResourceReader.fromYaml(yaml, ClusterLoadAssignment.class);
    }

    private static RouteConfiguration weightedRouteConfigWithMetadata(String name) {
        final String yaml = """
                name: %s
                virtual_hosts:
                  - name: weighted-vhost
                    domains: ["*"]
                    routes:
                      - match:
                          prefix: "/"
                        route:
                          metadata_match:
                            filter_metadata:
                              envoy.lb:
                                version: "v1"
                                stage: "prod"
                          weighted_clusters:
                            clusters:
                              - name: cluster-a
                                weight: 50
                                metadata_match:
                                  filter_metadata:
                                    envoy.lb:
                                      stage: "canary"
                              - name: cluster-b
                                weight: 50
                """.formatted(name);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static RouteConfiguration weightedRouteConfigWithMultipleFilterKeys(String name) {
        final String yaml = """
                name: %s
                virtual_hosts:
                  - name: weighted-vhost
                    domains: ["*"]
                    routes:
                      - match:
                          prefix: "/"
                        route:
                          metadata_match:
                            filter_metadata:
                              envoy.lb:
                                version: "v1"
                              custom.filter:
                                key1: "a"
                          weighted_clusters:
                            clusters:
                              - name: cluster-a
                                weight: 100
                                metadata_match:
                                  filter_metadata:
                                    custom.filter:
                                      key2: "b"
                """.formatted(name);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static RouteConfiguration weightedRouteConfigWithZeroWeight(String name,
                                                                       String cluster1,
                                                                       String cluster2) {
        final String yaml = """
                name: %s
                virtual_hosts:
                  - name: weighted-vhost
                    domains: ["*"]
                    routes:
                      - match:
                          prefix: "/"
                        route:
                          weighted_clusters:
                            clusters:
                              - name: %s
                                weight: 0
                              - name: %s
                                weight: 10
                """.formatted(name, cluster1, cluster2);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static final class RecordingWatcher implements SnapshotWatcher<ListenerSnapshot> {

        private final List<ListenerSnapshot> snapshots = new CopyOnWriteArrayList<>();
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onUpdate(@Nullable ListenerSnapshot snapshot, @Nullable Throwable t) {
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
            if (t != null) {
                errors.add(t);
            }
        }

        @Nullable
        ListenerSnapshot lastSnapshot() {
            if (snapshots.isEmpty()) {
                return null;
            }
            return snapshots.get(snapshots.size() - 1);
        }

        List<Throwable> errors() {
            return errors;
        }
    }
}
