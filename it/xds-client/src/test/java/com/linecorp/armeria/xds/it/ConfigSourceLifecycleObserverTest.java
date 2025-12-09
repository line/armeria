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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ConfigSourceLifecycleObserverTest {

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
    private static final String routeYaml =
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

    @Test
    void basicAdsStream() throws Exception {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml.formatted(server.httpPort()),
                                                               Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {

            // Set up resources to trigger ADS stream activity
            final Listener listener = XdsResourceReader.fromYaml(listenerYaml, Listener.class);
            final RouteConfiguration route = XdsResourceReader.fromYaml(routeYaml, RouteConfiguration.class);
            final Cluster cluster = XdsResourceReader.fromYaml(clusterYaml, Cluster.class);
            final ClusterLoadAssignment loadAssignment =
                    XdsResourceReader.fromYaml(endpointYaml, ClusterLoadAssignment.class);

            version.incrementAndGet();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster),
                                                     ImmutableList.of(loadAssignment),
                                                     ImmutableList.of(listener),
                                                     ImmutableList.of(route),
                                                     ImmutableList.of(), version.toString()));

            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");

            // Wait for ADS stream to fetch resources and verify metrics
            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);

                // Basic stream metrics for bootstrap cluster ADS stream
                assertThat(metrics).containsKey("armeria.xds.configsource.stream.active#" +
                                                "value{name=bootstrap-cluster,type=ads,xdsType=ads}");
                assertThat(ensureExistsAndGet(metrics, "armeria.xds.configsource.stream.opened#" +
                                                       "count{name=bootstrap-cluster,type=ads,xdsType=ads}"))
                        .isGreaterThan(0.0);
                assertThat(ensureExistsAndGet(metrics, "armeria.xds.configsource.stream.request#" +
                                                       "count{name=bootstrap-cluster,type=ads,xdsType=ads}"))
                        .isGreaterThan(0.0);
                assertThat(ensureExistsAndGet(metrics, "armeria.xds.configsource.stream.response#" +
                                                       "count{name=bootstrap-cluster,type=ads,xdsType=ads}"))
                        .isGreaterThan(0.0);
                assertThat(ensureExistsAndGet(metrics, "armeria.xds.configsource.resource.parse.success#" +
                                                       "count{name=bootstrap-cluster,type=ads,xdsType=ads}"))
                        .isGreaterThan(0);

                // No parse rejections should occur for valid resources
                final Double parseRejectedCount =
                        metrics.getOrDefault("armeria.xds.configsource.resource.parse.rejected#" +
                                             "count{name=bootstrap-cluster,type=ads,xdsType=ads}", 0.0);
                assertThat(parseRejectedCount).isEqualTo(0.0);
            });

            listenerRoot.close();
        }

        // Verify configsource metrics are cleaned up after XdsBootstrap closure
        await().untilAsserted(() -> {
            final var metrics = measureAll(meterRegistry);
            assertThat(metrics).isEmpty();
        });
    }

    //language=YAML
    private static final String separateConfigSourcesBootstrapYaml =
            """
                dynamic_resources:
                  lds_config:
                    api_config_source:
                      api_type: GRPC
                      grpc_services:
                        - envoy_grpc:
                            cluster_name: bootstrap-cluster
                  cds_config:
                    api_config_source:
                      api_type: GRPC
                      grpc_services:
                        - envoy_grpc:
                            cluster_name: bootstrap-cluster
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
    private static final String separateConfigSourcesListenerYaml =
            """
                name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                    stat_prefix: http
                    rds:
                      route_config_name: my-route
                      config_source:
                        api_config_source:
                          api_type: GRPC
                          grpc_services:
                            - envoy_grpc:
                                cluster_name: bootstrap-cluster
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """;

    //language=YAML
    private static final String separateConfigSourcesRouteYaml =
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
    private static final String separateConfigSourcesClusterYaml =
            """
                name: my-cluster
                type: EDS
                eds_cluster_config:
                  eds_config:
                    api_config_source:
                      api_type: GRPC
                      grpc_services:
                        - envoy_grpc:
                            cluster_name: bootstrap-cluster
                """;

    //language=YAML
    private static final String separateConfigSourcesEndpointYaml =
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

    @Test
    void separateConfigSources() throws Exception {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                separateConfigSourcesBootstrapYaml.formatted(server.httpPort()),
                Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {

            // Set up resources to trigger different config source activity
            final Listener listener =
                    XdsResourceReader.fromYaml(separateConfigSourcesListenerYaml, Listener.class);
            final RouteConfiguration route =
                    XdsResourceReader.fromYaml(separateConfigSourcesRouteYaml, RouteConfiguration.class);
            final Cluster cluster = XdsResourceReader.fromYaml(separateConfigSourcesClusterYaml, Cluster.class);
            final ClusterLoadAssignment loadAssignment =
                    XdsResourceReader.fromYaml(separateConfigSourcesEndpointYaml, ClusterLoadAssignment.class);

            version.incrementAndGet();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster),
                                                     ImmutableList.of(loadAssignment),
                                                     ImmutableList.of(listener),
                                                     ImmutableList.of(route),
                                                     ImmutableList.of(), version.toString()));

            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");

            // Wait for all config sources to be active and verify metrics for each
            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);

                // Verify each config source type has separate metrics streams
                final String[] xdsTypes = {"listener", "cluster", "route", "endpoint"};

                for (String xdsType : xdsTypes) {
                    // Verify stream is active and opened
                    assertThat(metrics).containsKey(String.format(
                            "armeria.xds.configsource.stream.active#value" +
                            "{name=bootstrap-cluster,type=api_config_source,xdsType=%s}",
                            xdsType));
                    assertThat(ensureExistsAndGet(metrics, String.format(
                            "armeria.xds.configsource.stream.opened#count" +
                            "{name=bootstrap-cluster,type=api_config_source,xdsType=%s}",
                            xdsType))).isGreaterThan(0.0);

                    // Verify request/response activity
                    assertThat(ensureExistsAndGet(metrics, String.format(
                            "armeria.xds.configsource.stream.request#count" +
                            "{name=bootstrap-cluster,type=api_config_source,xdsType=%s}",
                            xdsType))).isGreaterThan(0.0);
                    assertThat(ensureExistsAndGet(metrics, String.format(
                            "armeria.xds.configsource.stream.response#count" +
                            "{name=bootstrap-cluster,type=api_config_source,xdsType=%s}",
                            xdsType))).isGreaterThan(0.0);

                    // Verify successful resource parsing
                    assertThat(ensureExistsAndGet(metrics, String.format(
                            "armeria.xds.configsource.resource.parse.success#count" +
                            "{name=bootstrap-cluster,type=api_config_source,xdsType=%s}",
                            xdsType))).isGreaterThan(0.0);
                }
            });

            listenerRoot.close();
        }

        // Verify configsource metrics are cleaned up after XdsBootstrap closure
        await().untilAsserted(() -> {
            final var metrics = measureAll(meterRegistry);
            assertThat(metrics).isEmpty();
        });
    }

    //language=YAML
    private static final String malformedListenerYaml =
            """
                name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                """;

    //language=YAML
    private static final String malformedRouteYaml =
            """
              name: my-route
              virtual_hosts:
              - name: local_service1
                domains: []
            """;

    //language=YAML
    private static final String malformedClusterYaml =
            """
              name: my-cluster
              connect_timeout: -1s
            """;

    //language=YAML
    private static final String malformedEndpointYaml =
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

    static Stream<Arguments> resourceRejection_args() {
        // Create valid resources
        final Listener validListener =
                XdsResourceReader.fromYaml(separateConfigSourcesListenerYaml, Listener.class);
        final RouteConfiguration validRoute =
                XdsResourceReader.fromYaml(separateConfigSourcesRouteYaml, RouteConfiguration.class);
        final Cluster validCluster =
                XdsResourceReader.fromYaml(separateConfigSourcesClusterYaml, Cluster.class);
        final ClusterLoadAssignment validEndpoint =
                XdsResourceReader.fromYaml(separateConfigSourcesEndpointYaml, ClusterLoadAssignment.class);

        // Create malformed resources
        final Listener malformedListener =
                XdsResourceReader.fromYaml(malformedListenerYaml, Listener.class);
        final RouteConfiguration malformedRoute =
                XdsResourceReader.fromYaml(malformedRouteYaml, RouteConfiguration.class);
        final Cluster malformedCluster =
                XdsResourceReader.fromYaml(malformedClusterYaml, Cluster.class);
        final ClusterLoadAssignment malformedEndpoint =
                XdsResourceReader.fromYaml(malformedEndpointYaml, ClusterLoadAssignment.class);

        return Stream.of(
                Arguments.of(XdsType.LISTENER, Snapshot.create(
                        ImmutableList.of(validCluster),
                        ImmutableList.of(validEndpoint),
                        ImmutableList.of(malformedListener),
                        ImmutableList.of(validRoute),
                        ImmutableList.of(), String.valueOf(version.incrementAndGet()))),
                Arguments.of(XdsType.ROUTE, Snapshot.create(
                        ImmutableList.of(validCluster),
                        ImmutableList.of(validEndpoint),
                        ImmutableList.of(validListener),
                        ImmutableList.of(malformedRoute),
                        ImmutableList.of(), String.valueOf(version.incrementAndGet()))),
                Arguments.of(XdsType.CLUSTER, Snapshot.create(
                        ImmutableList.of(malformedCluster),
                        ImmutableList.of(validEndpoint),
                        ImmutableList.of(validListener),
                        ImmutableList.of(validRoute),
                        ImmutableList.of(), String.valueOf(version.incrementAndGet()))),
                Arguments.of(XdsType.ENDPOINT, Snapshot.create(
                        ImmutableList.of(validCluster),
                        ImmutableList.of(malformedEndpoint),
                        ImmutableList.of(validListener),
                        ImmutableList.of(validRoute),
                        ImmutableList.of(), String.valueOf(version.incrementAndGet())))
        );
    }

    @ParameterizedTest
    @MethodSource("resourceRejection_args")
    void resourceRejection(XdsType xdsType, Snapshot snapshot) throws Exception {
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                separateConfigSourcesBootstrapYaml.formatted(server.httpPort()),
                Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .meterRegistry(meterRegistry)
                                                     .build()) {

            cache.setSnapshot(GROUP, snapshot);
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");

            // Wait for the malformed resource to be rejected and verify rejection metrics
            await().untilAsserted(() -> {
                final var metrics = measureAll(meterRegistry);

                // Convert XdsType to metric xdsType string
                final String metricXdsType = xdsType.name().toLowerCase();

                // Verify that the malformed resource type has rejection count > 0
                final String rejectionMetricKey = String.format(
                        "armeria.xds.configsource.resource.parse.rejected#count" +
                        "{name=bootstrap-cluster,type=api_config_source,xdsType=%s}",
                        metricXdsType);
                assertThat(ensureExistsAndGet(metrics, rejectionMetricKey)).isGreaterThan(0.0);
            });

            listenerRoot.close();
        }

        // Verify configsource metrics are cleaned up after XdsBootstrap closure
        await().untilAsserted(() -> {
            final var metrics = measureAll(meterRegistry);
            assertThat(metrics).isEmpty();
        });
    }

    private static Double ensureExistsAndGet(Map<String, Double> meters, String key) {
        assertThat(meters).containsKey(key);
        return meters.get(key);
    }

    private static Map<String, Double> measureAll(MeterRegistry meterRegistry) {
        return measureAll(meterRegistry, key -> key.startsWith("armeria.xds.configsource"));
    }

    private static Map<String, Double> measureAll(MeterRegistry meterRegistry, Predicate<String> keyFilter) {
        return MoreMeters.measureAll(meterRegistry)
                         .entrySet().stream().filter(ele -> keyFilter.test(ele.getKey()))
                         .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
