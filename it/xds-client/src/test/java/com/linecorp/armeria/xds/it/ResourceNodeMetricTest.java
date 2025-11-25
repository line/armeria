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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterRoot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ResourceNodeMetricTest {

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

    //language=YAML
    private static final String bootstrapYaml =
            """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: bootstrap-cluster
                  lds_config:
                    ads: {}
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
                """;

    //language=YAML
    private static final String listenerYaml =
            """
                name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                    stat_prefix: http
                    route_config:
                      name: local_route
                      virtual_hosts:
                      - name: local_service1
                        domains: [ "*" ]
                        routes:
                          - match:
                              prefix: /
                            route:
                              cluster: my-cluster
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """;

    //language=YAML
    private static final String clusterYaml =
            """
                name: my-cluster
                type: EDS
                eds_cluster_config:
                  eds_config:
                    ads: {}
                """;

    //language=YAML
    private static final String endpointYaml =
            """
              cluster_name: my-cluster
              endpoints:
              - lb_endpoints:
                - endpoint:
                    address:
                      socket_address:
                        address: 127.0.0.1
                        port_value: 8080
            """;

    //language=YAML
    private static final String routeYaml =
            """
              name: route1
              virtual_hosts:
              - name: local_service1
                domains: [ "*" ]
                routes:
                  - match:
                      prefix: /
                    route:
                      cluster: my-cluster
            """;

    @Test
    void basicCase() throws Exception {
        final ClusterLoadAssignment loadAssignment =
                XdsResourceReader.fromYaml(endpointYaml, ClusterLoadAssignment.class);
        final Cluster cluster = XdsResourceReader.fromYaml(clusterYaml, Cluster.class);
        final Listener listener = XdsResourceReader.fromYaml(listenerYaml, Listener.class);
        final RouteConfiguration route = XdsResourceReader.fromYaml(routeYaml, RouteConfiguration.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(loadAssignment),
                                                 ImmutableList.of(listener), ImmutableList.of(route),
                                                 ImmutableList.of(), version.toString()));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml.formatted(server.httpPort()),
                                                               Bootstrap.class);
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {
            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });
            validatePrometheusTagConsistency(meterRegistry);

            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-listener,type=listener}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=local_route,type=route}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=local_service1,type=virtual_host}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 1.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });
            validatePrometheusTagConsistency(meterRegistry);

            listenerRoot.close();

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });
            validatePrometheusTagConsistency(meterRegistry);
        }

        await().untilAsserted(() -> {
            final var metrics = measureAll(meterRegistry);
            assertThat(metrics).isEmpty();
        });
    }

    private static List<Snapshot> staticRouteSnapshots() {

        //language=YAML
        final String listener =
                """
                    name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                    .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: my-route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                              - match:
                                  prefix: %s
                                route:
                                  cluster: my-cluster
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                    """;

        final Listener listener1 = XdsResourceReader.fromYaml(listener.formatted("/service1"),
                                                              Listener.class);
        final Listener listener2 = XdsResourceReader.fromYaml(listener.formatted("/service2"),
                                                              Listener.class);
        final Cluster cluster = XdsResourceReader.fromYaml(clusterYaml, Cluster.class);
        final ClusterLoadAssignment loadAssignment =
                XdsResourceReader.fromYaml(endpointYaml, ClusterLoadAssignment.class);

        return List.of(
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(loadAssignment),
                                ImmutableList.of(listener1),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(loadAssignment),
                                ImmutableList.of(listener2),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(loadAssignment),
                                ImmutableList.of(listener1),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet()))
        );
    }

    private static List<Snapshot> rdsRouteSnapshots() {
        //language=YAML
        final String rdsListenerYaml =
                """
                    name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                    .v3.HttpConnectionManager
                        stat_prefix: my-listener
                        rds:
                          route_config_name: my-route
                          config_source:
                            ads: {}
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                    """;

        //language=YAML
        final String routeYaml =
                """
                  name: my-route
                  virtual_hosts:
                  - name: local_service1
                    domains: [ "*" ]
                    routes:
                      - match:
                          prefix: %s
                        route:
                          cluster: my-cluster
                """;

        final Listener listener = XdsResourceReader.fromYaml(rdsListenerYaml, Listener.class);
        final RouteConfiguration route1 = XdsResourceReader.fromYaml(routeYaml.formatted("/service1"),
                                                                     RouteConfiguration.class);
        final RouteConfiguration route2 = XdsResourceReader.fromYaml(routeYaml.formatted("/service2"),
                                                                     RouteConfiguration.class);
        final Cluster cluster = XdsResourceReader.fromYaml(clusterYaml, Cluster.class);
        final ClusterLoadAssignment loadAssignment =
                XdsResourceReader.fromYaml(endpointYaml, ClusterLoadAssignment.class);

        return List.of(
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(loadAssignment),
                                ImmutableList.of(listener),
                                ImmutableList.of(route1),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(loadAssignment),
                                ImmutableList.of(listener),
                                ImmutableList.of(route2),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(loadAssignment),
                                ImmutableList.of(listener),
                                ImmutableList.of(route1),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet()))
        );
    }

    static Stream<Arguments> listenerRootWithRouteUpdate_args() {
        return Stream.of(
                Arguments.of(staticRouteSnapshots()),
                Arguments.of(rdsRouteSnapshots())
        );
    }

    @ParameterizedTest
    @MethodSource("listenerRootWithRouteUpdate_args")
    void listenerRootWithRouteUpdate(List<Snapshot> snapshots) throws Exception {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml.formatted(server.httpPort()),
                                                               Bootstrap.class);
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {

            // Step 1: Load initial resources with /service1 prefix
            cache.setSnapshot(GROUP, snapshots.get(0));

            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-listener,type=listener}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=my-route,type=route}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=local_service1,type=virtual_host}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 1.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });

            // Step 2: Change route prefix from /service1 to /service2
            cache.setSnapshot(GROUP, snapshots.get(1));

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-listener,type=listener}", 2.0,
                        "armeria.xds.resource.node.revision#value{name=my-route,type=route}", 2.0,
                        "armeria.xds.resource.node.revision#value{name=local_service1,type=virtual_host}", 2.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 2.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 2.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });

            // Step 3: Change route prefix back to /service1
            cache.setSnapshot(GROUP, snapshots.get(2));

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-listener,type=listener}", 3.0,
                        "armeria.xds.resource.node.revision#value{name=my-route,type=route}", 3.0,
                        "armeria.xds.resource.node.revision#value{name=local_service1,type=virtual_host}", 3.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 3.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 3.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });

            validatePrometheusTagConsistency(meterRegistry);

            // Step 4: Close the node root
            listenerRoot.close();

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });
            validatePrometheusTagConsistency(meterRegistry);
        }

        // Step 5: Verify all metrics are cleaned up after XdsBootstrap closure
        await().untilAsserted(() -> {
            final var metrics = measureAll(meterRegistry);
            assertThat(metrics).isEmpty();
        });
    }

    private static List<Snapshot> staticClusterSnapshots() {
        //language=YAML
        final String staticClusterYaml =
                """
                    name: my-cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: my-cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %s
                    """;

        final Cluster cluster1 = XdsResourceReader.fromYaml(staticClusterYaml.formatted("8080"), Cluster.class);
        final Cluster cluster2 = XdsResourceReader.fromYaml(staticClusterYaml.formatted("9090"), Cluster.class);
        final Cluster cluster3 = XdsResourceReader.fromYaml(staticClusterYaml.formatted("8080"), Cluster.class);

        return List.of(
                Snapshot.create(ImmutableList.of(cluster1),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster2),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster3),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet()))
        );
    }

    private static List<Snapshot> edsClusterSnapshots() {
        //language=YAML
        final String edsClusterYaml =
                """
                    name: my-cluster
                    type: EDS
                    eds_cluster_config:
                      eds_config:
                        ads: {}
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
                            port_value: %s
                """;

        final Cluster cluster = XdsResourceReader.fromYaml(edsClusterYaml, Cluster.class);
        final ClusterLoadAssignment endpoint1 = XdsResourceReader.fromYaml(
                endpointYaml.formatted("8080"), ClusterLoadAssignment.class);
        final ClusterLoadAssignment endpoint2 = XdsResourceReader.fromYaml(
                endpointYaml.formatted("9090"), ClusterLoadAssignment.class);
        final ClusterLoadAssignment endpoint3 = XdsResourceReader.fromYaml(
                endpointYaml.formatted("8080"), ClusterLoadAssignment.class);

        return List.of(
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(endpoint1),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(endpoint2),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(endpoint3),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet()))
        );
    }

    static Stream<Arguments> clusterRootWithClusterUpdate_args() {
        return Stream.of(
                Arguments.of(staticClusterSnapshots()),
                Arguments.of(edsClusterSnapshots())
        );
    }

    @ParameterizedTest
    @MethodSource("clusterRootWithClusterUpdate_args")
    void clusterRootWithClusterUpdate(List<Snapshot> snapshots) throws Exception {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml.formatted(server.httpPort()),
                                                               Bootstrap.class);
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {

            // Step 1: Load initial cluster with port 8080
            cache.setSnapshot(GROUP, snapshots.get(0));

            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot("my-cluster");

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 1.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });

            // Step 2: Change cluster endpoint port from 8080 to 9090
            cache.setSnapshot(GROUP, snapshots.get(1));

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 2.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 2.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });

            // Step 3: Change cluster endpoint port back to 8080
            cache.setSnapshot(GROUP, snapshots.get(2));

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 3.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 3.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });

            validatePrometheusTagConsistency(meterRegistry);

            // Step 4: Close the cluster root
            clusterRoot.close();

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });
            validatePrometheusTagConsistency(meterRegistry);
        }

        // Step 5: Verify all metrics are cleaned up after XdsBootstrap closure
        await().untilAsserted(() -> {
            final var metrics = measureAll(meterRegistry);
            assertThat(metrics).isEmpty();
        });
    }

    private static List<Snapshot> staticEndpointUpdateSnapshots() {
        //language=YAML
        final String listenerYaml =
                """
                    name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                    .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: my-route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                              - match:
                                  prefix: /
                                route:
                                  cluster: my-cluster
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                    """;

        //language=YAML
        final String staticClusterYaml =
                """
                    name: my-cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: my-cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %s
                    """;

        final Listener listener = XdsResourceReader.fromYaml(listenerYaml, Listener.class);
        final Cluster cluster1 = XdsResourceReader.fromYaml(staticClusterYaml.formatted("8080"), Cluster.class);
        final Cluster cluster2 = XdsResourceReader.fromYaml(staticClusterYaml.formatted("9090"), Cluster.class);
        final Cluster cluster3 = XdsResourceReader.fromYaml(staticClusterYaml.formatted("8080"), Cluster.class);

        return List.of(
                Snapshot.create(ImmutableList.of(cluster1),
                                ImmutableList.of(),
                                ImmutableList.of(listener),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster2),
                                ImmutableList.of(),
                                ImmutableList.of(listener),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster3),
                                ImmutableList.of(),
                                ImmutableList.of(listener),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet()))
        );
    }

    private static List<Snapshot> edsEndpointUpdateSnapshots() {
        //language=YAML
        final String listenerYaml =
                """
                    name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                    .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: my-route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                              - match:
                                  prefix: /
                                route:
                                  cluster: my-cluster
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                    """;

        //language=YAML
        final String edsClusterYaml =
                """
                    name: my-cluster
                    type: EDS
                    eds_cluster_config:
                      eds_config:
                        ads: {}
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
                            port_value: %s
                """;

        final Listener listener = XdsResourceReader.fromYaml(listenerYaml, Listener.class);
        final Cluster cluster = XdsResourceReader.fromYaml(edsClusterYaml, Cluster.class);
        final ClusterLoadAssignment endpoint1 = XdsResourceReader.fromYaml(
                endpointYaml.formatted("8080"), ClusterLoadAssignment.class);
        final ClusterLoadAssignment endpoint2 = XdsResourceReader.fromYaml(
                endpointYaml.formatted("9090"), ClusterLoadAssignment.class);
        final ClusterLoadAssignment endpoint3 = XdsResourceReader.fromYaml(
                endpointYaml.formatted("8080"), ClusterLoadAssignment.class);

        return List.of(
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(endpoint1),
                                ImmutableList.of(listener),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(endpoint2),
                                ImmutableList.of(listener),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                Snapshot.create(ImmutableList.of(cluster),
                                ImmutableList.of(endpoint3),
                                ImmutableList.of(listener),
                                ImmutableList.of(),
                                ImmutableList.of(), String.valueOf(version.incrementAndGet()))
        );
    }

    static Stream<Arguments> listenerRootWithEndpointUpdate_args() {
        return Stream.of(
                Arguments.of(staticEndpointUpdateSnapshots()),
                Arguments.of(edsEndpointUpdateSnapshots())
        );
    }

    @ParameterizedTest
    @MethodSource("listenerRootWithEndpointUpdate_args")
    void listenerRootWithEndpointUpdate(List<Snapshot> snapshots) throws Exception {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml.formatted(server.httpPort()),
                                                               Bootstrap.class);
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {

            // Step 1: Load initial resources with endpoint port 8080
            cache.setSnapshot(GROUP, snapshots.get(0));

            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-listener,type=listener}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=my-route,type=route}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=local_service1,type=virtual_host}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 1.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 1.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });

            // Step 2: Change endpoint port from 8080 to 9090
            cache.setSnapshot(GROUP, snapshots.get(1));

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-listener,type=listener}", 2.0,
                        "armeria.xds.resource.node.revision#value{name=my-route,type=route}", 2.0,
                        "armeria.xds.resource.node.revision#value{name=local_service1,type=virtual_host}", 2.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 2.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 2.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });

            // Step 3: Change endpoint port back to 8080
            cache.setSnapshot(GROUP, snapshots.get(2));

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=my-listener,type=listener}", 3.0,
                        "armeria.xds.resource.node.revision#value{name=my-route,type=route}", 3.0,
                        "armeria.xds.resource.node.revision#value{name=local_service1,type=virtual_host}", 3.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=cluster}", 3.0,
                        "armeria.xds.resource.node.revision#value{name=my-cluster,type=endpoint}", 3.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });

            validatePrometheusTagConsistency(meterRegistry);

            // Step 4: Close the listener root
            listenerRoot.close();

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);
                assertThat(metrics).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0
                ));
                assertErrorAndMissingMetricsAreZero(metrics);
            });
            validatePrometheusTagConsistency(meterRegistry);
        }

        // Step 5: Verify all metrics are cleaned up after XdsBootstrap closure
        await().untilAsserted(() -> {
            final var metrics = measureAll(meterRegistry);
            assertThat(metrics).isEmpty();
        });
    }

    private static List<Arguments> listenerRootWithErrorData() {
        //language=YAML
        final String rdsListenerYaml =
                """
                    name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                    .v3.HttpConnectionManager
                        stat_prefix: my-listener
                        rds:
                          route_config_name: my-route
                          config_source:
                            ads: {}
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                    """;

        //language=YAML
        final String malformedListenerYaml =
                """
                    name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                    .v3.HttpConnectionManager
                    """;

        //language=YAML
        final String malformedRouteYaml =
                """
                  name: my-route
                  virtual_hosts:
                  - name: local_service1
                    domains: []
                """;

        //language=YAML
        final String validRouteYaml =
                """
                  name: my-route
                  virtual_hosts:
                  - name: local_service1
                    domains: [ "*" ]
                    routes:
                      - match:
                          prefix: /
                        route:
                          cluster: my-cluster
                """;

        //language=YAML
        final String malformedClusterYaml =
                """
                  name: my-cluster
                  connect_timeout: -1s
                """;

        //language=YAML
        final String edsClusterYaml =
                """
                    name: my-cluster
                    type: EDS
                    eds_cluster_config:
                      eds_config:
                        ads: {}
                    """;

        //language=YAML
        final String malformedEndpointYaml =
                """
                  cluster_name: my-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 1234
                    priority: 1234
                """;

        final Listener validListener = XdsResourceReader.fromYaml(rdsListenerYaml, Listener.class);
        final Listener malformedListener = XdsResourceReader.fromYaml(malformedListenerYaml, Listener.class);
        final RouteConfiguration validRoute = XdsResourceReader.fromYaml(validRouteYaml,
                                                                         RouteConfiguration.class);
        final RouteConfiguration malformedRoute = XdsResourceReader.fromYaml(malformedRouteYaml,
                                                                             RouteConfiguration.class);
        final Cluster validCluster = XdsResourceReader.fromYaml(clusterYaml, Cluster.class);
        final Cluster malformedCluster = XdsResourceReader.fromYaml(malformedClusterYaml, Cluster.class);
        final Cluster edsCluster = XdsResourceReader.fromYaml(edsClusterYaml, Cluster.class);
        final ClusterLoadAssignment validEndpoint =
                XdsResourceReader.fromYaml(endpointYaml, ClusterLoadAssignment.class);
        final ClusterLoadAssignment malformedEndpoint = XdsResourceReader.fromYaml(malformedEndpointYaml,
                                                                                   ClusterLoadAssignment.class);

        return List.of(
                // Malformed listener error
                Arguments.of(
                        Snapshot.create(ImmutableList.of(validCluster),
                                        ImmutableList.of(validEndpoint),
                                        ImmutableList.of(malformedListener),
                                        ImmutableList.of(validRoute),
                                        ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                        "armeria.xds.resource.node.error#count{name=my-listener,type=listener}"
                ),
                // Malformed route error
                Arguments.of(
                        Snapshot.create(ImmutableList.of(validCluster),
                                        ImmutableList.of(validEndpoint),
                                        ImmutableList.of(validListener),
                                        ImmutableList.of(malformedRoute),
                                        ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                        "armeria.xds.resource.node.error#count{name=my-route,type=route}"
                ),
                // Malformed cluster error
                Arguments.of(
                        Snapshot.create(ImmutableList.of(malformedCluster),
                                        ImmutableList.of(),
                                        ImmutableList.of(validListener),
                                        ImmutableList.of(validRoute),
                                        ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                        "armeria.xds.resource.node.error#count{name=my-cluster,type=cluster}"
                ),
                // Malformed endpoint error
                Arguments.of(
                        Snapshot.create(ImmutableList.of(edsCluster),
                                        ImmutableList.of(malformedEndpoint),
                                        ImmutableList.of(validListener),
                                        ImmutableList.of(validRoute),
                                        ImmutableList.of(), String.valueOf(version.incrementAndGet())),
                        "armeria.xds.resource.node.error#count{name=my-cluster,type=endpoint}"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("listenerRootWithErrorData")
    void listenerRootWithError(Snapshot snapshot, String expectedErrorMetric) throws Exception {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml.formatted(server.httpPort()),
                                                               Bootstrap.class);
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {

            // Set the snapshot with malformed resource
            cache.setSnapshot(GROUP, snapshot);

            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");

            // Wait for the error to be reported in metrics
            await().untilAsserted(() -> {
                final var errorMetrics = measureAll(meterRegistry, key -> key
                        .startsWith("armeria.xds.resource.node.error#count"));
                assertThat(errorMetrics).containsKey(expectedErrorMetric);
                assertThat(errorMetrics.get(expectedErrorMetric)).isGreaterThanOrEqualTo(1);

                final var revisionMetrics = measureAll(meterRegistry, key -> key
                        .startsWith("armeria.xds.resource.node.revision#value"));
                // Verify bootstrap metrics remain zero, listener metric may be 0.0 for validation errors
                assertThat(revisionMetrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0
                ));
            });

            validatePrometheusTagConsistency(meterRegistry);

            // Close the listener root
            listenerRoot.close();

            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry, key -> key
                        .contains("armeria.xds.resource.node.revision#value"));
                // After closing, only bootstrap metrics should remain
                assertThat(metrics).containsAllEntriesOf(Map.of(
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=cluster}", 0.0,
                        "armeria.xds.resource.node.revision#value{name=bootstrap-cluster,type=endpoint}", 0.0
                ));
            });
            validatePrometheusTagConsistency(meterRegistry);
        }

        // Verify metrics are cleaned up after XdsBootstrap closure
        // Note: Error counters may persist, but revision gauges should be cleaned up
        await().untilAsserted(() -> {
            final var metrics = measureAll(meterRegistry, key -> key.contains("revision#value"));
            assertThat(metrics).isEmpty();
        });
    }

    private static Map<String, Double> measureAll(final MeterRegistry meterRegistry) {
        return measureAll(meterRegistry, key -> key.startsWith("armeria.xds.resource.node"));
    }

    private static Map<String, Double> measureAll(final MeterRegistry meterRegistry,
                                                  Predicate<String> keyFilter) {
        return MoreMeters.measureAll(meterRegistry)
                         .entrySet().stream().filter(ele -> keyFilter.test(ele.getKey()))
                         .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static void assertErrorAndMissingMetricsAreZero(Map<String, Double> metrics) {
        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            final String metricName = entry.getKey();
            if (metricName.contains("error#value") || metricName.contains("missing#value")) {
                assertThat(entry.getValue()).isEqualTo(0.0);
            }
        }
    }

    private static void validatePrometheusTagConsistency(MeterRegistry meterRegistry) {
        final Map<String, Set<String>> metricNameToTagKeys = new HashMap<>();
        for (Meter meter : meterRegistry.getMeters()) {
            final String metricName = meter.getId().getName();

            final Set<String> tagKeys = meter.getId().getTags().stream().map(Tag::getKey)
                                             .collect(ImmutableSet.toImmutableSet());
            final Set<String> existingTagKeys = metricNameToTagKeys.get(metricName);
            if (existingTagKeys == null) {
                metricNameToTagKeys.put(metricName, tagKeys);
            } else {
                assertThat(existingTagKeys).isEqualTo(tagKeys);
            }
        }
    }
}
