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

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class XdsLoadBalancerLifecycleObserverTest {

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
    private static final String staticBootstrapYaml =
            """
                static_resources:
                  listeners:
                    - name: my-listener
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
                  clusters:
                    - name: my-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: my-cluster
                        endpoints:
                        - lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: 1234
                """;

    //language=YAML
    private static final String dynamicBootstrapYaml =
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

    @Test
    void basicCase() {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(staticBootstrapYaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build();
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener")) {
            // Wait for the static cluster to be loaded and metrics to be registered
            await().untilAsserted(() -> {
                final var lbMetrics = measureAll(meterRegistry);
                assertThat(lbMetrics).containsExactlyInAnyOrderEntriesOf(
                        ImmutableMap.<String, Double>builder()
                                // Revision metrics
                                .put("armeria.xds.lb.endpoints.updated.revision#value{cluster=my-cluster}", 0.0)
                                .put("armeria.xds.lb.resource.updated.revision#value{cluster=my-cluster}", 0.0)
                                .put("armeria.xds.lb.state.rejected.revision#value{cluster=my-cluster}", 0.0)
                                .put("armeria.xds.lb.state.updated.revision#value{cluster=my-cluster}", 0.0)
                                // Count metrics
                                .put("armeria.xds.lb.endpoints.updated.count#count{cluster=my-cluster}", 1.0)
                                .put("armeria.xds.lb.resource.updated.count#count{cluster=my-cluster}", 1.0)
                                .put("armeria.xds.lb.state.updated.count#count{cluster=my-cluster}", 1.0)
                                .put("armeria.xds.lb.state.rejected.count#count{cluster=my-cluster}", 0.0)
                                // Membership metrics
                                .put("armeria.xds.lb.membership.healthy#value" +
                                     "{cluster=my-cluster,priority=0,region=,sub_zone=,zone=}", 1.0)
                                .put("armeria.xds.lb.membership.total#value" +
                                     "{cluster=my-cluster,priority=0,region=,sub_zone=,zone=}", 1.0)
                                .put("armeria.xds.lb.membership.degraded#value" +
                                     "{cluster=my-cluster,priority=0,region=,sub_zone=,zone=}", 0.0)
                                // Load balancer state metrics
                                .put("armeria.xds.lb.state.load.degraded#value" +
                                     "{cluster=my-cluster,priority=0}", 0.0)
                                .put("armeria.xds.lb.state.load.healthy#value" +
                                     "{cluster=my-cluster,priority=0}", 100.0)
                                .put("armeria.xds.lb.state.panic#value{cluster=my-cluster,priority=0}", 0.0)
                                .put("armeria.xds.lb.state.subsets#value{cluster=my-cluster}", 0.0)
                                // Locality and zone metrics
                                .put("armeria.xds.lb.locality.weight#value" +
                                     "{cluster=my-cluster,priority=0,region=,sub_zone=,zone=}", 0.0)
                                .put("armeria.xds.lb.zar.local.percentage#value" +
                                     "{cluster=my-cluster,priority=0}", 0.0)
                                .put("armeria.xds.lb.zar.residual.percentage#value" +
                                     "{cluster=my-cluster,priority=0,region=,sub_zone=,zone=}", 0.0)
                                .build());
            });
        }

        // Verify all metrics are cleaned up after XdsBootstrap closure
        await().untilAsserted(() -> {
            final var metersMap = measureAll(meterRegistry);
            assertThat(metersMap).isEmpty();
        });
    }

    @Test
    void cdsWithRevisionUpdates() throws Exception {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                dynamicBootstrapYaml.formatted(server.httpPort()), Bootstrap.class);

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
        final String edsClusterYaml =
                """
                    name: my-cluster
                    type: EDS
                    eds_cluster_config:
                      eds_config:
                        ads: {}
                    """;

        //language=YAML
        final String endpointYaml1 =
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
        final String endpointYaml2 =
                """
                  cluster_name: my-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 9090
                """;

        final Listener listener = XdsResourceReader.fromYaml(listenerYaml, Listener.class);
        final Cluster cluster = XdsResourceReader.fromYaml(edsClusterYaml, Cluster.class);
        final ClusterLoadAssignment endpoint1 = XdsResourceReader.fromYaml(endpointYaml1,
                                                                           ClusterLoadAssignment.class);
        final ClusterLoadAssignment endpoint2 = XdsResourceReader.fromYaml(endpointYaml2,
                                                                           ClusterLoadAssignment.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {

            // Step 1: Set initial snapshot with first endpoint
            version.incrementAndGet();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(endpoint1),
                                                     ImmutableList.of(listener), ImmutableList.of(),
                                                     ImmutableList.of(), version.toString()));

            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");

            await().untilAsserted(() -> {
                final var lbMetrics = measureAll(meterRegistry, key ->
                        key.contains("updated.revision") || key.contains("updated.count"));
                assertThat(lbMetrics).containsAllEntriesOf(ImmutableMap.of(
                        // Check that revisions are initially 1 after first update
                        "armeria.xds.lb.endpoints.updated.revision#value{cluster=my-cluster}", 1.0,
                        "armeria.xds.lb.resource.updated.revision#value{cluster=my-cluster}", 1.0,
                        "armeria.xds.lb.state.updated.revision#value{cluster=my-cluster}", 1.0,
                        // Count metrics show activity (1 for the first endpoint update)
                        "armeria.xds.lb.endpoints.updated.count#count{cluster=my-cluster}", 1.0,
                        "armeria.xds.lb.resource.updated.count#count{cluster=my-cluster}", 1.0,
                        "armeria.xds.lb.state.updated.count#count{cluster=my-cluster}", 1.0
                ));
            });

            // Step 2: Update endpoint to trigger revision increment
            version.incrementAndGet();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(endpoint2),
                                                     ImmutableList.of(listener), ImmutableList.of(),
                                                     ImmutableList.of(), version.toString()));

            await().untilAsserted(() -> {
                final var lbMetrics = measureAll(meterRegistry, key ->
                        key.contains("updated.revision") || key.contains("updated.count"));
                assertThat(lbMetrics).containsAllEntriesOf(ImmutableMap.of(
                        // Check that revisions are incremented to 2 after second update
                        "armeria.xds.lb.endpoints.updated.revision#value{cluster=my-cluster}", 2.0,
                        "armeria.xds.lb.resource.updated.revision#value{cluster=my-cluster}", 2.0,
                        "armeria.xds.lb.state.updated.revision#value{cluster=my-cluster}", 2.0,
                        "armeria.xds.lb.endpoints.updated.count#count{cluster=my-cluster}", 4.0,
                        "armeria.xds.lb.resource.updated.count#count{cluster=my-cluster}", 4.0,
                        "armeria.xds.lb.state.updated.count#count{cluster=my-cluster}", 4.0
                ));
            });

            listenerRoot.close();
        }

        // Verify all metrics are cleaned up after XdsBootstrap closure
        await().untilAsserted(() -> {
            final var metersMap = measureAll(meterRegistry);
            assertThat(metersMap).isEmpty();
        });
    }

    @Test
    void complexCase() throws Exception {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();

        //language=YAML
        final String zoneAwareBootstrapYaml =
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
                    - name: local-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: local-cluster
                        endpoints:
                        - locality:
                            region: us-east-1
                          lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: 8080
                            health_status: HEALTHY
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: 8081
                            health_status: HEALTHY
                        - locality:
                            region: us-west-100
                          lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: 8082
                node:
                  locality:
                    region: us-east-1
                cluster_manager:
                  local_cluster_name: local-cluster
                """;

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                zoneAwareBootstrapYaml.formatted(server.httpPort()), Bootstrap.class);

        //language=YAML
        final String complexEndpointsYaml =
                """
                  cluster_name: my-cluster
                  policy:
                    overprovisioning_factor: 100
                  endpoints:
                  - priority: 0
                    locality:
                      region: us-east-1
                    load_balancing_weight: 50
                    lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 8080
                      load_balancing_weight: 150
                      metadata:
                        filter_metadata:
                          "envoy.lb":
                            version: v1
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 8081
                      load_balancing_weight: 50
                      health_status: DEGRADED
                      metadata:
                        filter_metadata:
                          "envoy.lb":
                            version: v2
                  - priority: 0
                    locality:
                      region: us-west-1
                    load_balancing_weight: 200
                    lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 8082
                      load_balancing_weight: 100
                      metadata:
                        filter_metadata:
                          "envoy.lb":
                            version: v1
                  - priority: 0
                    locality:
                      region: us-west-2
                    load_balancing_weight: 200
                    lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 8083
                      load_balancing_weight: 200
                      metadata:
                        filter_metadata:
                          "envoy.lb":
                            version: v2
                  # Priority 1 - Secondary endpoints
                  - priority: 1
                    locality:
                      region: us-west-2
                    load_balancing_weight: 300
                    lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 9090
                      load_balancing_weight: 100
                      metadata:
                        filter_metadata:
                          "envoy.lb":
                            version: v1
                """;

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
        final String clusterYaml =
                """
                    name: my-cluster
                    type: EDS
                    eds_cluster_config:
                      eds_config:
                        ads: {}
                    lb_subset_config:
                      fallback_policy: ANY_ENDPOINT
                      subset_selectors:
                      - keys:
                        - version
                    common_lb_config:
                      zone_aware_lb_config:
                        routing_enabled:
                          value: 100
                        min_cluster_size: 1
                      healthy_panic_threshold:
                        value: 0
                    """;

        final Listener listener = XdsResourceReader.fromYaml(listenerYaml, Listener.class);
        final Cluster cluster = XdsResourceReader.fromYaml(clusterYaml, Cluster.class);
        final ClusterLoadAssignment complexEndpoints =
                XdsResourceReader.fromYaml(complexEndpointsYaml, ClusterLoadAssignment.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {

            // Set snapshot with complex multi-priority, multi-locality endpoints
            version.incrementAndGet();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster),
                                                     ImmutableList.of(complexEndpoints),
                                                     ImmutableList.of(listener), ImmutableList.of(),
                                                     ImmutableList.of(), version.toString()));

            try (ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener")) {
                await().untilAsserted(() -> {
                    final var lbMetrics = measureAll(meterRegistry, key -> key.contains("cluster=my-cluster"));

                    // Priority 0 locality-specific membership metrics
                    assertThat(lbMetrics).containsAllEntriesOf(ImmutableMap.<String, Double>builder()
                            .put("armeria.xds.lb.membership.healthy#value" +
                                 "{cluster=my-cluster,priority=0,region=us-east-1,sub_zone=,zone=}", 1.0)
                            .put("armeria.xds.lb.membership.total#value" +
                                 "{cluster=my-cluster,priority=0,region=us-east-1,sub_zone=,zone=}", 2.0)
                            .put("armeria.xds.lb.membership.degraded#value" +
                                 "{cluster=my-cluster,priority=0,region=us-east-1,sub_zone=,zone=}", 1.0)
                            .put("armeria.xds.lb.membership.healthy#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-1,sub_zone=,zone=}", 1.0)
                            .put("armeria.xds.lb.membership.total#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-1,sub_zone=,zone=}", 1.0)
                            .put("armeria.xds.lb.membership.degraded#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-1,sub_zone=,zone=}", 0.0)
                            .put("armeria.xds.lb.membership.healthy#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-2,sub_zone=,zone=}", 1.0)
                            .put("armeria.xds.lb.membership.total#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-2,sub_zone=,zone=}", 1.0)
                            .put("armeria.xds.lb.membership.degraded#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-2,sub_zone=,zone=}", 0.0)
                            .build());

                    // Priority 1 locality-specific membership metrics (us-west-2: 1 healthy endpoint)
                    assertThat(lbMetrics).containsAllEntriesOf(ImmutableMap.of(
                            "armeria.xds.lb.membership.healthy#value" +
                            "{cluster=my-cluster,priority=1,region=us-west-2,sub_zone=,zone=}", 1.0,
                            "armeria.xds.lb.membership.total#value" +
                            "{cluster=my-cluster,priority=1,region=us-west-2,sub_zone=,zone=}", 1.0,
                            "armeria.xds.lb.membership.degraded#value" +
                            "{cluster=my-cluster,priority=1,region=us-west-2,sub_zone=,zone=}", 0.0
                    ));

                    // Verify load balancer state metrics per priority
                    // Note: With 3 healthy endpoints in priority 0, 1 healthy endpoint in priority 1
                    assertThat(lbMetrics).containsAllEntriesOf(ImmutableMap.of(
                            "armeria.xds.lb.state.load.healthy#value{cluster=my-cluster,priority=0}", 75.0,
                            "armeria.xds.lb.state.load.degraded#value{cluster=my-cluster,priority=0}", 0.0,
                            "armeria.xds.lb.state.panic#value{cluster=my-cluster,priority=0}", 0.0,
                            "armeria.xds.lb.state.load.healthy#value{cluster=my-cluster,priority=1}", 25.0,
                            "armeria.xds.lb.state.load.degraded#value{cluster=my-cluster,priority=1}", 0.0,
                            "armeria.xds.lb.state.panic#value{cluster=my-cluster,priority=1}", 0.0,
                            // Subset load balancing metrics - should show number of created subsets (v1, v2)
                            "armeria.xds.lb.state.subsets#value{cluster=my-cluster}", 2.0
                    ));

                    // Verify locality weights reflect the configured load balancing weights
                    assertThat(lbMetrics).containsAllEntriesOf(ImmutableMap.<String, Double>builder()
                            // Priority 0 locality weights (actual values from test output)
                            .put("armeria.xds.lb.locality.weight#value" +
                                 "{cluster=my-cluster,priority=0,region=us-east-1,sub_zone=,zone=}", 50.0)
                            .put("armeria.xds.lb.locality.weight#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-1,sub_zone=,zone=}", 200.0)
                            .put("armeria.xds.lb.locality.weight#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-2,sub_zone=,zone=}", 200.0)
                            // Priority 1 locality weight (weight=300 for us-west-2)
                            .put("armeria.xds.lb.locality.weight#value" +
                                 "{cluster=my-cluster,priority=1,region=us-west-2,sub_zone=,zone=}", 300.0)
                            .build());

                    // Zone-aware routing (ZAR) metrics
                    assertThat(lbMetrics).containsAllEntriesOf(ImmutableMap.<String, Double>builder()
                            .put("armeria.xds.lb.zar.local.percentage#value" +
                                 "{cluster=my-cluster,priority=0}", 0.5)
                            .put("armeria.xds.lb.zar.residual.percentage#value" +
                                 "{cluster=my-cluster,priority=0,region=us-east-1,sub_zone=,zone=}", 0.0)
                            .put("armeria.xds.lb.zar.residual.percentage#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-1,sub_zone=,zone=}", 0.5)
                            .put("armeria.xds.lb.zar.residual.percentage#value" +
                                 "{cluster=my-cluster,priority=0,region=us-west-2,sub_zone=,zone=}", 0.5)
                            .build());
                });
            }
        }

        // Verify all metrics are cleaned up after XdsBootstrap closure
        await().untilAsserted(() -> {
            final var metersMap = measureAll(meterRegistry);
            assertThat(metersMap).isEmpty();
        });
    }

    private static Map<String, Double> measureAll(final MeterRegistry meterRegistry) {
        return measureAll(meterRegistry, key -> key.startsWith("armeria.xds.lb"));
    }

    private static Map<String, Double> measureAll(final MeterRegistry meterRegistry,
                                                  Predicate<String> keyFilter) {
        return MoreMeters.measureAll(meterRegistry)
                         .entrySet().stream().filter(ele -> keyFilter.test(ele.getKey()))
                         .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
