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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsType;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.controlplane.server.exception.RequestException;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;

class XdsControlPlaneErrorHandlingTest {

    private static final String GROUP = "key";
    private static final String LISTENER_NAME = "listener";
    private static final String ROUTE_NAME = "route";
    private static final String CLUSTER_NAME = "cluster";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";
    private static final int PORT_V1 = 8080;
    private static final long TIMEOUT_V1 = 1;
    private static final String STAT_PREFIX_V1 = "http1";
    private static final String ROUTE_PREFIX_V1 = "/";

    private static final int PORT_V2 = 9090;
    private static final long TIMEOUT_V2 = 2;
    private static final String STAT_PREFIX_V2 = "http2";
    private static final String ROUTE_PREFIX_V2 = "/v2";

    private static final AtomicLong version = new AtomicLong();
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final NackTracker nackTracker = new NackTracker();

    private static final List<ApiType> PROTOCOLS = ImmutableList.of(
            AGGREGATED_GRPC, GRPC, AGGREGATED_DELTA_GRPC, DELTA_GRPC);

    private static final List<XdsType> TARGETS = ImmutableList.of(
            XdsType.LISTENER, XdsType.ROUTE, XdsType.CLUSTER, XdsType.ENDPOINT);

    @RegisterExtension
    @Order(0)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(nackTracker, cache);
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

    static Stream<Arguments> nackRecoveryCases() {
        return PROTOCOLS.stream().flatMap(proto ->
                TARGETS.stream().map(target -> Arguments.of(proto, target)));
    }

    @ParameterizedTest
    @MethodSource("nackRecoveryCases")
    void nackAndRecovery(ApiType apiType, XdsType malformedTarget) throws Exception {
        cache.setSnapshot(GROUP, emptySnapshot());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapYaml(apiType).formatted(server.httpPort()), Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(LISTENER_NAME)) {
            final RecordingWatcher<ListenerSnapshot> watcher = new RecordingWatcher<>();
            listenerRoot.addSnapshotWatcher(watcher);

            // Step 1: send a snapshot with one PGV-invalid resource
            final int nacksBefore = nackTracker.nackCount();
            cache.setSnapshot(GROUP, malformedSnapshot(apiType, malformedTarget));

            // Step 2: verify NACK arrives at the server
            // (Armeria applies a 3 s backoff before sending, so give Awaitility enough time)
            await().untilAsserted(() ->
                    assertThat(nackTracker.nackCount()).isGreaterThan(nacksBefore));

            // Step 3: set valid resources
            cache.setSnapshot(GROUP, validSnapshot(apiType));

            // Step 4: verify the client recovered and delivered the new snapshot
            awaitExpectedState(watcher);
        }
    }

    static Stream<Arguments> protocols() {
        return PROTOCOLS.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("protocols")
    void connectionClosureAndRecovery(ApiType apiType) throws Exception {
        cache.setSnapshot(GROUP, emptySnapshot());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapYaml(apiType).formatted(server.httpPort()), Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(LISTENER_NAME)) {
            final RecordingWatcher<ListenerSnapshot> watcher = new RecordingWatcher<>();
            listenerRoot.addSnapshotWatcher(watcher);

            // Step 1: set V1 snapshot and verify it is received
            cache.setSnapshot(GROUP, validSnapshot(apiType));
            awaitExpectedState(watcher);

            // Step 2: drain and cancel all open gRPC streams
            final ServiceRequestContextCaptor captor = server.requestContextCaptor();
            await().untilAsserted(() -> assertThat(captor.isEmpty()).isFalse());
            for (ServiceRequestContext ctx : captor.all()) {
                ctx.cancel();
            }

            // Step 3: push V2 snapshot
            cache.setSnapshot(GROUP, validSnapshotV2(apiType));

            // Step 4: verify the client reconnected and received the V2 snapshot
            awaitExpectedState(watcher, STAT_PREFIX_V2, ROUTE_PREFIX_V2, TIMEOUT_V2, PORT_V2);
        }
    }

    private static Snapshot malformedSnapshot(ApiType apiType, XdsType malformedTarget) {
        final Listener validListener = listenerYaml(apiType);
        final RouteConfiguration validRoute = routeYaml();
        final Cluster validEdsCluster = clusterYaml(apiType);
        final ClusterLoadAssignment validEndpoint = endpointYaml();

        switch (malformedTarget) {
            case LISTENER: {
                final Listener malformedListener = XdsResourceReader.fromYaml(
                        malformedListenerYaml(), Listener.class);
                return Snapshot.create(
                        ImmutableList.of(validEdsCluster),
                        ImmutableList.of(validEndpoint),
                        ImmutableList.of(malformedListener),
                        ImmutableList.of(validRoute),
                        ImmutableList.of(),
                        String.valueOf(version.incrementAndGet()));
            }
            case ROUTE: {
                final RouteConfiguration malformedRoute = XdsResourceReader.fromYaml(
                        malformedRouteYaml(), RouteConfiguration.class);
                return Snapshot.create(
                        ImmutableList.of(validEdsCluster),
                        ImmutableList.of(validEndpoint),
                        ImmutableList.of(validListener),
                        ImmutableList.of(malformedRoute),
                        ImmutableList.of(),
                        String.valueOf(version.incrementAndGet()));
            }
            case CLUSTER: {
                final Cluster malformedCluster = XdsResourceReader.fromYaml(
                        malformedClusterYaml(), Cluster.class);
                return Snapshot.create(
                        ImmutableList.of(malformedCluster),
                        ImmutableList.of(),
                        ImmutableList.of(validListener),
                        ImmutableList.of(validRoute),
                        ImmutableList.of(),
                        String.valueOf(version.incrementAndGet()));
            }
            case ENDPOINT: {
                final ClusterLoadAssignment malformedEndpoint = XdsResourceReader.fromYaml(
                        malformedEndpointYaml(), ClusterLoadAssignment.class);
                return Snapshot.create(
                        ImmutableList.of(validEdsCluster),
                        ImmutableList.of(malformedEndpoint),
                        ImmutableList.of(validListener),
                        ImmutableList.of(validRoute),
                        ImmutableList.of(),
                        String.valueOf(version.incrementAndGet()));
            }
            default:
                throw new IllegalArgumentException("Unexpected target: " + malformedTarget);
        }
    }

    private static Snapshot validSnapshot(ApiType apiType) {
        return Snapshot.create(
                ImmutableList.of(clusterYaml(apiType)),
                ImmutableList.of(endpointYaml()),
                ImmutableList.of(listenerYaml(apiType)),
                ImmutableList.of(routeYaml()),
                ImmutableList.of(),
                String.valueOf(version.incrementAndGet()));
    }

    private static Snapshot validSnapshotV2(ApiType apiType) {
        return Snapshot.create(
                ImmutableList.of(XdsResourceReader.fromYaml(clusterYamlString(apiType, TIMEOUT_V2),
                                                            Cluster.class)),
                ImmutableList.of(endpointYaml(PORT_V2)),
                ImmutableList.of(XdsResourceReader.fromYaml(listenerYamlString(apiType, STAT_PREFIX_V2),
                                                            Listener.class)),
                ImmutableList.of(routeYaml(ROUTE_PREFIX_V2)),
                ImmutableList.of(),
                String.valueOf(version.incrementAndGet()));
    }

    private static void awaitExpectedState(RecordingWatcher<ListenerSnapshot> listenerWatcher)
            throws Exception {
        awaitExpectedState(listenerWatcher, STAT_PREFIX_V1, ROUTE_PREFIX_V1, TIMEOUT_V1, PORT_V1);
    }

    private static void awaitExpectedState(RecordingWatcher<ListenerSnapshot> listenerWatcher,
                                           String statPrefix, String routePrefix,
                                           long timeout, int port) throws Exception {
        await().untilAsserted(() -> {
            final ListenerSnapshot listenerSnapshot = listenerWatcher.lastSnapshot();
            assertThat(listenerSnapshot).isNotNull();
            final Listener listener = listenerSnapshot.xdsResource().resource();
            assertThat(statPrefix(listener)).isEqualTo(statPrefix);

            final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
            assertThat(routeSnapshot).isNotNull();
            final RouteConfiguration route = routeSnapshot.xdsResource().resource();
            assertThat(route.getVirtualHosts(0).getRoutes(0).getMatch().getPrefix())
                    .isEqualTo(routePrefix);

            final RouteEntry routeEntry = routeSnapshot.virtualHostSnapshots().get(0).routeEntries().get(0);
            final ClusterSnapshot clusterSnapshot = routeEntry.clusterSnapshot();
            assertThat(clusterSnapshot).isNotNull();
            final Cluster cluster = clusterSnapshot.xdsResource().resource();
            assertThat(cluster.getConnectTimeout().getSeconds()).isEqualTo(timeout);

            final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
            assertThat(endpointSnapshot).isNotNull();
            final ClusterLoadAssignment loadAssignment = endpointSnapshot.xdsResource().resource();
            assertThat(endpointPort(loadAssignment)).isEqualTo(port);
        });
    }

    private static boolean isAds(ApiType t) {
        return t == AGGREGATED_GRPC || t == AGGREGATED_DELTA_GRPC;
    }

    private static String statPrefix(Listener listener) throws Exception {
        final Any apiListener = listener.getApiListener().getApiListener();
        final HttpConnectionManager hcm = apiListener.unpack(HttpConnectionManager.class);
        return hcm.getStatPrefix();
    }

    private static int endpointPort(ClusterLoadAssignment loadAssignment) {
        return loadAssignment.getEndpoints(0)
                             .getLbEndpoints(0)
                             .getEndpoint()
                             .getAddress()
                             .getSocketAddress()
                             .getPortValue();
    }

    private static Listener listenerYaml(ApiType apiType) {
        return XdsResourceReader.fromYaml(listenerYamlString(apiType), Listener.class);
    }

    private static String listenerYamlString(ApiType apiType) {
        return listenerYamlString(apiType, STAT_PREFIX_V1);
    }

    private static String listenerYamlString(ApiType apiType, String statPrefix) {
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
                """.formatted(LISTENER_NAME, statPrefix, ROUTE_NAME,
                              apiType.name(), BOOTSTRAP_CLUSTER_NAME);
    }

    private static RouteConfiguration routeYaml() {
        return routeYaml(ROUTE_PREFIX_V1);
    }

    private static RouteConfiguration routeYaml(String routePrefix) {
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
                """.formatted(ROUTE_NAME, routePrefix, CLUSTER_NAME);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static Cluster clusterYaml(ApiType apiType) {
        return XdsResourceReader.fromYaml(clusterYamlString(apiType), Cluster.class);
    }

    private static String clusterYamlString(ApiType apiType) {
        return clusterYamlString(apiType, TIMEOUT_V1);
    }

    private static String clusterYamlString(ApiType apiType, long timeoutSeconds) {
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

    private static ClusterLoadAssignment endpointYaml() {
        return endpointYaml(PORT_V1);
    }

    private static ClusterLoadAssignment endpointYaml(int port) {
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
                """.formatted(CLUSTER_NAME, port);
        return XdsResourceReader.fromYaml(yaml, ClusterLoadAssignment.class);
    }

    private static String malformedListenerYaml() {
        // PGV-invalid: HCM Any has correct @type but no stat_prefix or http_filters
        //language=YAML
        return """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                """.formatted(LISTENER_NAME);
    }

    private static String malformedRouteYaml() {
        // PGV-invalid: domains must be non-empty
        //language=YAML
        return """
                name: %s
                virtual_hosts:
                - name: local_service1
                  domains: []
                """.formatted(ROUTE_NAME);
    }

    private static String malformedClusterYaml() {
        // PGV-invalid: connect_timeout must be non-negative
        //language=YAML
        return """
                name: %s
                connect_timeout: -1s
                """.formatted(CLUSTER_NAME);
    }

    private static String malformedEndpointYaml() {
        // PGV-invalid: priority must be in range [0, 128)
        //language=YAML
        return """
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: 127.0.0.1
                          port_value: 1234
                  priority: 1234
                """.formatted(CLUSTER_NAME);
    }

    private static String bootstrapYaml(ApiType apiType) {
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

    private static Snapshot emptySnapshot() {
        return Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                               ImmutableList.of(), ImmutableList.of(), "0");
    }

    private static final class NackTracker implements DiscoveryServerCallbacks {
        private final AtomicInteger nackCount = new AtomicInteger();

        @Override
        public void onV3StreamRequest(long streamId, DiscoveryRequest request) throws RequestException {
            if (request.hasErrorDetail() && request.getErrorDetail().getCode() != 0) {
                nackCount.incrementAndGet();
            }
        }

        @Override
        public void onV3StreamDeltaRequest(long streamId,
                                           DeltaDiscoveryRequest request) throws RequestException {
            if (request.hasErrorDetail() && request.getErrorDetail().getCode() != 0) {
                nackCount.incrementAndGet();
            }
        }

        int nackCount() {
            return nackCount.get();
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
    }
}
