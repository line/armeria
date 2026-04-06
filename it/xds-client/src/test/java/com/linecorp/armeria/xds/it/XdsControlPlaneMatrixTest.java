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

import static io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType.AGGREGATED_DELTA_GRPC;
import static io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType.AGGREGATED_GRPC;
import static io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType.DELTA_GRPC;
import static io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType.GRPC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.MissingXdsResourceException;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsType;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

class XdsControlPlaneMatrixTest {

    private static final String GROUP = "key";
    private static final String LISTENER_NAME = "listener";
    private static final String ROUTE_NAME = "route";
    private static final String CLUSTER_NAME = "cluster";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";
    private static final int PORT_V1 = 8080;
    private static final int PORT_V2 = 9090;
    private static final long TIMEOUT_V1 = 1;
    private static final long TIMEOUT_V2 = 2;
    private static final String STAT_PREFIX_V1 = "http1";
    private static final String STAT_PREFIX_V2 = "http2";
    private static final String ROUTE_PREFIX_V1 = "/";
    private static final String ROUTE_PREFIX_V2 = "/v2";

    private static final AtomicLong version = new AtomicLong();
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    private static final List<ApiType> PROTOCOLS = ImmutableList.of(
            AGGREGATED_GRPC, GRPC, AGGREGATED_DELTA_GRPC, DELTA_GRPC);

    private static final List<XdsType> TARGETS = ImmutableList.of(
            XdsType.LISTENER, XdsType.ROUTE, XdsType.CLUSTER, XdsType.ENDPOINT);

    private static final List<XdsType> SOTW_TYPES = ImmutableList.of(XdsType.LISTENER, XdsType.CLUSTER);

    @RegisterExtension
    @Order(0)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
            sb.http(0);
        }
    };

    @RegisterExtension
    @Order(1)
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    private static List<Scenario> scenarios() {
        final List<Scenario> result = new ArrayList<>();
        for (int mask = 0; mask < (1 << TARGETS.size()); mask++) {
            final List<XdsType> statics = new ArrayList<>();
            for (int i = 0; i < TARGETS.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    statics.add(TARGETS.get(i));
                }
            }
            result.add(new Scenario(statics.toArray(new XdsType[0])));
        }
        return result;
    }

    static Stream<Arguments> matrixCases() {
        final List<Arguments> arguments = new ArrayList<>();
        for (ApiType apiType : PROTOCOLS) {
            for (Scenario scenario : scenarios()) {
                for (XdsType target : TARGETS) {
                    if (scenario.isStatic(target)) {
                        // can't modify/remove static resources
                        continue;
                    }
                    for (Operation operation : Operation.values()) {
                        arguments.add(Arguments.of(apiType, scenario, target, operation));
                    }
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("matrixCases")
    void controlPlaneMatrix(ApiType apiType, Scenario scenario, XdsType target, Operation operation)
            throws Exception {
        cache.setSnapshot(GROUP, emptySnapshot());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapYaml(apiType, scenario).formatted(server.httpPort()), Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(LISTENER_NAME)) {
            final RecordingWatcher<ListenerSnapshot> watcher = new RecordingWatcher<>();
            listenerRoot.addSnapshotWatcher(watcher);

            applyOperation(apiType, scenario, target, operation, watcher);
        }
    }

    private static void applyOperation(ApiType apiType, Scenario scenario, XdsType target,
                                       Operation operation,
                                       RecordingWatcher<ListenerSnapshot> listenerWatcher) throws Exception {
        final ResourceVariants baseline = ResourceVariants.v1();
        cache.setSnapshot(GROUP, snapshotFor(apiType, scenario, baseline, null));
        awaitExpectedState(baseline, listenerWatcher);

        switch (operation) {
            case MODIFY:
                cache.setSnapshot(GROUP, snapshotFor(apiType, scenario,
                                                     baseline.with(target, Variant.V2), null));
                awaitExpectedState(baseline.with(target, Variant.V2), listenerWatcher);
                return;
            case DELETE:
                if (apiType == AGGREGATED_GRPC) {
                    // java-control-plane discards watchers for missing resources for ads grpc
                    return;
                }
                cache.setSnapshot(GROUP, snapshotFor(apiType, scenario, baseline, target));
                if (isAds(apiType) && SOTW_TYPES.contains(target)) {
                    // only sotw types report missing resource on deletion
                    awaitMissing(target, listenerWatcher);
                } else if (isDelta(apiType)) {
                    awaitMissing(target, listenerWatcher);
                }
                cache.setSnapshot(GROUP, snapshotFor(apiType, scenario,
                                                     baseline.with(target, Variant.V2), null));
                awaitExpectedState(baseline.with(target, Variant.V2), listenerWatcher);
        }
    }

    private static boolean isAds(ApiType t) {
        return t == AGGREGATED_GRPC || t == AGGREGATED_DELTA_GRPC;
    }

    private static boolean isDelta(ApiType t) {
        return t == DELTA_GRPC || t == AGGREGATED_DELTA_GRPC;
    }

    private static void awaitExpectedState(ResourceVariants variants,
                                           RecordingWatcher<ListenerSnapshot> listenerWatcher)
            throws Exception {
        await().untilAsserted(() -> {
            final ListenerSnapshot listenerSnapshot = listenerWatcher.lastSnapshot();
            assertThat(listenerSnapshot).isNotNull();
            final Listener listener = listenerSnapshot.xdsResource().resource();
            assertThat(statPrefix(listener)).isEqualTo(expectedStatPrefix(variants.listener));

            final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
            assertThat(routeSnapshot).isNotNull();
            final RouteConfiguration route = routeSnapshot.xdsResource().resource();
            assertThat(route.getVirtualHosts(0).getRoutes(0).getMatch().getPrefix())
                    .isEqualTo(expectedRoutePrefix(variants.route));

            final RouteEntry routeEntry = routeSnapshot.virtualHostSnapshots().get(0).routeEntries().get(0);
            final ClusterSnapshot clusterSnapshot = routeEntry.clusterSnapshot();
            assertThat(clusterSnapshot).isNotNull();
            final Cluster cluster = clusterSnapshot.xdsResource().resource();
            assertThat(cluster.getConnectTimeout().getSeconds()).isEqualTo(expectedTimeout(variants.cluster));

            final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
            assertThat(endpointSnapshot).isNotNull();
            final ClusterLoadAssignment loadAssignment = endpointSnapshot.xdsResource().resource();
            assertThat(endpointPort(loadAssignment)).isEqualTo(expectedEndpointPort(variants.endpoint));
        });
    }

    private static void awaitMissing(XdsType target, RecordingWatcher<ListenerSnapshot> listenerWatcher) {
        final String expectedName = switch (target) {
            case LISTENER -> LISTENER_NAME;
            case ROUTE    -> ROUTE_NAME;
            default       -> CLUSTER_NAME; // CLUSTER and ENDPOINT share CLUSTER_NAME
        };
        await().untilAsserted(() -> assertThat(listenerWatcher.errors())
                .anyMatch(error -> isMissingResource(error, target, expectedName)));
    }

    private static boolean isMissingResource(Throwable error, XdsType type, String name) {
        if (!(error instanceof MissingXdsResourceException exception)) {
            return false;
        }
        return exception.type() == type && exception.name().equals(name);
    }

    private static String expectedStatPrefix(Variant variant) {
        return variant == Variant.V1 ? STAT_PREFIX_V1 : STAT_PREFIX_V2;
    }

    private static String expectedRoutePrefix(Variant variant) {
        return variant == Variant.V1 ? ROUTE_PREFIX_V1 : ROUTE_PREFIX_V2;
    }

    private static long expectedTimeout(Variant variant) {
        return variant == Variant.V1 ? TIMEOUT_V1 : TIMEOUT_V2;
    }

    private static int expectedEndpointPort(Variant variant) {
        return variant == Variant.V1 ? PORT_V1 : PORT_V2;
    }

    private static int endpointPort(ClusterLoadAssignment loadAssignment) {
        return loadAssignment.getEndpoints(0)
                             .getLbEndpoints(0)
                             .getEndpoint()
                             .getAddress()
                             .getSocketAddress()
                             .getPortValue();
    }

    private static Snapshot snapshotFor(ApiType apiType, Scenario scenario, ResourceVariants variants,
                                        XdsType removedTarget) {
        final List<Listener> listeners = new ArrayList<>();
        if (!scenario.isStatic(XdsType.LISTENER) && removedTarget != XdsType.LISTENER) {
            listeners.add(listenerYaml(apiType, scenario, variants.listener, variants.route));
        }

        final List<RouteConfiguration> routes = new ArrayList<>();
        if (!scenario.isStatic(XdsType.ROUTE) && removedTarget != XdsType.ROUTE) {
            routes.add(routeYaml(variants.route));
        }

        final List<Cluster> clusters = new ArrayList<>();
        if (!scenario.isStatic(XdsType.CLUSTER) && removedTarget != XdsType.CLUSTER) {
            clusters.add(clusterYaml(apiType, scenario, variants.cluster, variants.endpoint));
        }

        final List<ClusterLoadAssignment> endpoints = new ArrayList<>();
        if (!scenario.isStatic(XdsType.ENDPOINT) && removedTarget != XdsType.ENDPOINT) {
            endpoints.add(endpointYaml(variants.endpoint));
        }

        return Snapshot.create(clusters, endpoints, listeners, routes, ImmutableList.of(),
                               String.valueOf(version.incrementAndGet()));
    }

    private static Snapshot emptySnapshot() {
        return Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                               ImmutableList.of(), ImmutableList.of(), "0");
    }

    private static String statPrefix(Listener listener) throws Exception {
        final Any apiListener = listener.getApiListener().getApiListener();
        final HttpConnectionManager hcm = apiListener.unpack(HttpConnectionManager.class);
        return hcm.getStatPrefix();
    }

    private static Listener listenerYaml(ApiType apiType, Scenario scenario, Variant listenerVariant,
                                         Variant routeVariant) {
        return XdsResourceReader.fromYaml(listenerYamlString(apiType, scenario, listenerVariant, routeVariant),
                                          Listener.class);
    }

    private static String listenerYamlString(ApiType apiType, Scenario scenario, Variant listenerVariant,
                                             Variant routeVariant) {
        final String statPrefix = expectedStatPrefix(listenerVariant);
        if (scenario.isStatic(XdsType.ROUTE)) {
            //language=YAML
            return """
                    name: %s
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network\
                    .http_connection_manager.v3.HttpConnectionManager
                        stat_prefix: %s
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                            - match:
                                prefix: %s
                              route:
                                cluster: %s
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                    """.formatted(LISTENER_NAME, statPrefix, expectedRoutePrefix(routeVariant), CLUSTER_NAME);
        }
        if (isAds(apiType)) {
            //language=YAML
            return """
                    name: %s
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network\
                    .http_connection_manager.v3.HttpConnectionManager
                        stat_prefix: %s
                        rds:
                          route_config_name: %s
                          config_source:
                            ads: {}
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                    """.formatted(LISTENER_NAME, statPrefix, ROUTE_NAME);
        }
        //language=YAML
        return """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: %s
                    rds:
                      route_config_name: %s
                      config_source:
                        api_config_source:
                          api_type: %s
                          grpc_services:
                          - envoy_grpc:
                              cluster_name: %s
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(LISTENER_NAME, statPrefix, ROUTE_NAME, apiType.name(), BOOTSTRAP_CLUSTER_NAME);
    }

    private static RouteConfiguration routeYaml(Variant routeVariant) {
        //language=YAML
        final String yaml = """
                name: %s
                virtual_hosts:
                - name: local_service1
                  domains: [ "*" ]
                  routes:
                  - match:
                      prefix: %s
                    route:
                      cluster: %s
                """.formatted(ROUTE_NAME, expectedRoutePrefix(routeVariant), CLUSTER_NAME);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static Cluster clusterYaml(ApiType apiType, Scenario scenario, Variant clusterVariant,
                                       Variant endpointVariant) {
        return XdsResourceReader.fromYaml(
                clusterYamlString(apiType, scenario, clusterVariant, endpointVariant),
                Cluster.class);
    }

    private static String clusterYamlString(ApiType apiType, Scenario scenario, Variant clusterVariant,
                                            Variant endpointVariant) {
        final long timeoutSeconds = expectedTimeout(clusterVariant);
        if (scenario.isStatic(XdsType.ENDPOINT)) {
            //language=YAML
            return """
                    name: %s
                    type: STATIC
                    connect_timeout: %ss
                    load_assignment:
                      cluster_name: %s
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %s
                    """.formatted(CLUSTER_NAME, timeoutSeconds, CLUSTER_NAME,
                                  expectedEndpointPort(endpointVariant));
        }
        if (isAds(apiType)) {
            //language=YAML
            return """
                    name: %s
                    type: EDS
                    connect_timeout: %ss
                    eds_cluster_config:
                      eds_config:
                        ads: {}
                    """.formatted(CLUSTER_NAME, timeoutSeconds);
        }
        //language=YAML
        return """
                name: %s
                type: EDS
                connect_timeout: %ss
                eds_cluster_config:
                  eds_config:
                    api_config_source:
                      api_type: %s
                      grpc_services:
                      - envoy_grpc:
                          cluster_name: %s
                """.formatted(CLUSTER_NAME, timeoutSeconds, apiType.name(), BOOTSTRAP_CLUSTER_NAME);
    }

    private static ClusterLoadAssignment endpointYaml(Variant endpointVariant) {
        //language=YAML
        final String yaml = """
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: 127.0.0.1
                          port_value: %s
                """.formatted(CLUSTER_NAME, expectedEndpointPort(endpointVariant));
        return XdsResourceReader.fromYaml(yaml, ClusterLoadAssignment.class);
    }

    private static String bootstrapYaml(ApiType apiType, Scenario scenario) {
        final String dynamicResources;
        if (!isAds(apiType)) {
            //language=YAML
            dynamicResources = """
              lds_config:
                api_config_source:
                  api_type: %s
                  grpc_services:
                    - envoy_grpc:
                        cluster_name: %s
              cds_config:
                api_config_source:
                  api_type: %s
                  grpc_services:
                    - envoy_grpc:
                        cluster_name: %s
            """.formatted(apiType.name(), BOOTSTRAP_CLUSTER_NAME,
                          apiType.name(), BOOTSTRAP_CLUSTER_NAME);
        } else {
            //language=YAML
            dynamicResources = """
              ads_config:
                api_type: %s
                grpc_services:
                  - envoy_grpc:
                      cluster_name: %s
              lds_config:
                ads: {}
              cds_config:
                ads: {}
            """.formatted(apiType.name(), BOOTSTRAP_CLUSTER_NAME);
        }

        final StringBuilder staticResources = new StringBuilder();
        staticResources.append("  clusters:\n");
        appendListItem(staticResources, bootstrapClusterYaml(), 2);
        if (scenario.isStatic(XdsType.CLUSTER)) {
            appendListItem(staticResources, clusterYamlString(apiType, scenario, Variant.V1, Variant.V1), 2);
        }
        if (scenario.isStatic(XdsType.LISTENER)) {
            staticResources.append("  listeners:\n");
            appendListItem(staticResources, listenerYamlString(apiType, scenario, Variant.V1, Variant.V1), 2);
        }

        //language=YAML
        return """
            dynamic_resources:
            %s
            static_resources:
            %s
            """.formatted(dynamicResources.stripTrailing(),
                          staticResources.toString().stripTrailing());
    }

    private static String bootstrapClusterYaml() {
        //language=YAML
        return """
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
                            port_value: %%s
                """.formatted(BOOTSTRAP_CLUSTER_NAME, BOOTSTRAP_CLUSTER_NAME);
    }

    private static void appendListItem(StringBuilder sb, String yaml, int indent) {
        final String trimmed = yaml.stripTrailing();
        final String[] lines = trimmed.split("\\n");
        if (lines.length == 0) {
            return;
        }
        final String padding = " ".repeat(indent);
        sb.append(padding).append("- ").append(lines[0]).append('\n');
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                sb.append('\n');
            } else {
                sb.append(padding).append("  ").append(lines[i]).append('\n');
            }
        }
    }

    private enum Operation {
        MODIFY,
        DELETE,
    }

    private enum Variant {
        V1,
        V2
    }

    private static final class Scenario {
        private final EnumSet<XdsType> staticTypes;

        Scenario(XdsType... staticTypes) {
            this.staticTypes = staticTypes.length == 0 ?
                               EnumSet.noneOf(XdsType.class)
                                                       : EnumSet.copyOf(Arrays.asList(staticTypes));
        }

        boolean isStatic(XdsType type) {
            return staticTypes.contains(type);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Scenario)) {
                return false;
            }
            return staticTypes.equals(((Scenario) o).staticTypes);
        }

        @Override
        public int hashCode() {
            return staticTypes.hashCode();
        }

        @Override
        public String toString() {
            return "Scenario{static=" + staticTypes + '}';
        }
    }

    private static final class ResourceVariants {
        private final Variant listener;
        private final Variant route;
        private final Variant cluster;
        private final Variant endpoint;

        private ResourceVariants(Variant listener, Variant route, Variant cluster, Variant endpoint) {
            this.listener = listener;
            this.route = route;
            this.cluster = cluster;
            this.endpoint = endpoint;
        }

        private static ResourceVariants v1() {
            return new ResourceVariants(Variant.V1, Variant.V1, Variant.V1, Variant.V1);
        }

        private ResourceVariants with(XdsType target, Variant variant) {
            return switch (target) {
                case LISTENER -> new ResourceVariants(variant, route, cluster, endpoint);
                case ROUTE    -> new ResourceVariants(listener, variant, cluster, endpoint);
                case CLUSTER  -> new ResourceVariants(listener, route, variant, endpoint);
                case ENDPOINT -> new ResourceVariants(listener, route, cluster, variant);
                default       -> throw new IllegalStateException("Unexpected target: " + target);
            };
        }
    }

    private static final class RecordingWatcher<T> implements SnapshotWatcher<T> {

        private final List<T> snapshots = new CopyOnWriteArrayList<>();
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onUpdate(T snapshot, Throwable t) {
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
            if (t != null) {
                errors.add(t);
            }
        }

        T lastSnapshot() {
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
