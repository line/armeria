/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import static com.linecorp.armeria.xds.XdsTestResources.BOOTSTRAP_CLUSTER_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * This class ensures that the dynamic bootstrap configuration example at
 * <a href="https://www.envoyproxy.io/docs/envoy/latest/configuration/overview/examples#dynamic">
 * Dynamic Example</a> is parsed and fetched correctly.
 */
class DynamicResourcesTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        final Listener listener = XdsTestResources.exampleListener("listener_0",
                                                                   "local_route", BOOTSTRAP_CLUSTER_NAME);
        final RouteConfiguration routeConfiguration =
                XdsTestResources.routeConfiguration("local_route", "some_service");
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(exampleCluster()),
                                ImmutableList.of(exampleEndpoint()),
                                ImmutableList.of(listener),
                                ImmutableList.of(routeConfiguration),
                                ImmutableList.of(), "1"));
    }

    @Test
    void basicCase() throws Exception {
        final String listenerName = "listener_0";
        final String routeName = "local_route";
        final String clusterName = "some_service";
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(listenerName);

            final TestResourceWatcher watcher = new TestResourceWatcher();
            listenerRoot.addSnapshotWatcher(watcher);
            final Listener expectedListener =
                    cache.getSnapshot(GROUP).listeners().resources().get(listenerName);
            final ListenerSnapshot listenerSnapshot =
                    watcher.blockingChanged(ListenerSnapshot.class);
            assertThat(listenerSnapshot.xdsResource().resource()).isEqualTo(expectedListener);

            final RouteConfiguration expectedRoute =
                    cache.getSnapshot(GROUP).routes().resources().get(routeName);
            final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
            assertThat(routeSnapshot.xdsResource().resource()).isEqualTo(expectedRoute);

            final Cluster expectedCluster =
                    cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            final ClusterSnapshot clusterSnapshot = routeSnapshot.clusterSnapshots().get(0);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            final ClusterLoadAssignment expectedEndpoint =
                    cache.getSnapshot(GROUP).endpoints().resources().get(clusterName);
            final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
            assertThat(endpointSnapshot.xdsResource().resource()).isEqualTo(expectedEndpoint);

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(watcher.events()).isEmpty());
        }
    }

    static ClusterLoadAssignment exampleEndpoint() {
        return ClusterLoadAssignment
                .newBuilder()
                .setClusterName("some_service")
                .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                 .addLbEndpoints(XdsTestResources.endpoint("127.0.0.1", 1234)))
                .build();
    }

    static Cluster exampleCluster() {
        final ApiConfigSource apiConfigSource = ApiConfigSource
                .newBuilder()
                .setApiType(ApiType.GRPC)
                .setTransportApiVersion(ApiVersion.V3)
                .addGrpcServices(
                        io.envoyproxy.envoy.config.core.v3.GrpcService
                                .newBuilder()
                                .setEnvoyGrpc(EnvoyGrpc.newBuilder()
                                                       .setClusterName(BOOTSTRAP_CLUSTER_NAME)))
                .build();
        return Cluster
                .newBuilder()
                .setName("some_service")
                .setConnectTimeout(Duration.newBuilder().setNanos(25_000_000))
                .setLbPolicy(LbPolicy.ROUND_ROBIN)
                .setType(DiscoveryType.EDS)
                .setEdsClusterConfig(
                        EdsClusterConfig
                                .newBuilder()
                                .setEdsConfig(ConfigSource
                                                      .newBuilder()
                                                      .setResourceApiVersion(ApiVersion.V3)
                                                      .setApiConfigSource(apiConfigSource)))
                      .build();
    }
}
