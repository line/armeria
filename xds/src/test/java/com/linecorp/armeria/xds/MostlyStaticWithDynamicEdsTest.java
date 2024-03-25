/*
 * Copyright 2024 LINE Corporation
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
import static com.linecorp.armeria.xds.XdsTestResources.bootstrapCluster;
import static com.linecorp.armeria.xds.XdsTestResources.createCluster;
import static com.linecorp.armeria.xds.XdsTestResources.loadAssignment;
import static com.linecorp.armeria.xds.XdsTestResources.staticResourceListener;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

/**
 * This class ensures that the dynamic bootstrap configuration example at
 * <a href="https://www.envoyproxy.io/docs/envoy/latest/configuration/overview/examples#mostly-static-with-dynamic-eds">
 * Mostly static with dynamic EDS</a> is parsed and fetched correctly.
 */
class MostlyStaticWithDynamicEdsTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(),
                                    ImmutableList.of(loadAssignment("cluster", "127.0.0.1", 8080)),
                                    ImmutableList.of(),
                                    ImmutableList.of(),
                                    ImmutableList.of(), "1"));
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @Test
    void basicCase() throws Exception {
        final ConfigSource configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME);
        final Cluster bootstrapCluster = bootstrapCluster(server.httpUri(), BOOTSTRAP_CLUSTER_NAME);
        final Cluster staticCluster = createCluster("cluster");
        final Listener listener = staticResourceListener();
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, listener,
                                                               bootstrapCluster, staticCluster);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final TestResourceWatcher watcher = new TestResourceWatcher();
            listenerRoot.addSnapshotWatcher(watcher);
            final ListenerSnapshot listenerSnapshot =
                    watcher.blockingChanged(ListenerSnapshot.class);
            assertThat(listenerSnapshot.xdsResource().resource()).isEqualTo(listener);

            final RouteConfiguration expectedRoute =
                    listener.getApiListener()
                            .getApiListener()
                            .unpack(HttpConnectionManager.class)
                            .getRouteConfig();
            final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
            assertThat(routeSnapshot.xdsResource().resource()).isEqualTo(expectedRoute);

            final ClusterSnapshot clusterSnapshot = routeSnapshot.clusterSnapshots().get(0);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(staticCluster);
            final ClusterLoadAssignment expectedEndpoint =
                    cache.getSnapshot(GROUP).endpoints().resources().get("cluster");
            final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
            assertThat(endpointSnapshot.xdsResource().resource()).isEqualTo(expectedEndpoint);
        }
    }
}
