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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.DynamicResources;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class MultiConfigSourceTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache1 = new SimpleCache<>(node -> GROUP);
    private static final SimpleCache<String> cache2 = new SimpleCache<>(node -> GROUP);
    static final String bootstrapClusterName1 = "bootstrap-cluster-1";
    static final String bootstrapClusterName2 = "bootstrap-cluster-2";

    @RegisterExtension
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache1);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };
    @RegisterExtension
    static final ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache2);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        final ConfigSource configSource = XdsTestResources.adsConfigSource();
        final Cluster cluster = Cluster.newBuilder()
                                       .setName("cluster1")
                                       .setType(DiscoveryType.EDS)
                                       .setEdsClusterConfig(EdsClusterConfig.newBuilder()
                                                                            .setEdsConfig(configSource))
                                       .build();
        final Listener listener = XdsTestResources.exampleListener("listener1", "route1");
        final RouteConfiguration route = XdsTestResources.routeConfiguration("route1", "cluster1");
        cache1.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(cluster),
                        ImmutableList.of(),
                        ImmutableList.of(listener),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));
        cache2.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(),
                        ImmutableList.of(TestResources.createEndpoint("cluster1", "127.0.0.1", 8080)),
                        ImmutableList.of(),
                        ImmutableList.of(route),
                        ImmutableList.of(),
                        "1"));
    }

    @Test
    void basicCase() throws Exception {
        final Bootstrap bootstrap = bootstrap();
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final TestResourceWatcher watcher = new TestResourceWatcher();
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot("cluster1");
            clusterRoot.addSnapshotWatcher(watcher);
            final ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);

            // Updates are propagated for the initial value
            final ClusterLoadAssignment expectedCluster =
                    cache2.getSnapshot(GROUP).endpoints().resources().get("cluster1");
            assertThat(clusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(expectedCluster);

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(watcher.events()).isEmpty());
        }
    }

    @Test
    void fromListener() throws Exception {
        final Bootstrap bootstrap = bootstrap();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final TestResourceWatcher watcher = new TestResourceWatcher();
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener1");
            listenerRoot.addSnapshotWatcher(watcher);
            final ListenerSnapshot listenerSnapshot = watcher.blockingChanged(ListenerSnapshot.class);

            // Updates are propagated for the initial value
            final ClusterLoadAssignment expected =
                    cache2.getSnapshot(GROUP).endpoints().resources().get("cluster1");
            assertThat(listenerSnapshot.routeSnapshot().clusterSnapshots()
                                       .get(0).endpointSnapshot().xdsResource().resource()).isEqualTo(expected);

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(watcher.events()).isEmpty());
        }
    }

    private static Bootstrap bootstrap() {
        final ClusterLoadAssignment loadAssignment1 =
                XdsTestResources.loadAssignment(bootstrapClusterName1,
                                                server1.httpUri().getHost(), server1.httpPort());
        final ClusterLoadAssignment loadAssignment2 =
                XdsTestResources.loadAssignment(bootstrapClusterName2,
                                                server2.httpUri().getHost(), server2.httpPort());
        final Cluster staticCluster1 = XdsTestResources.createStaticCluster(bootstrapClusterName1,
                                                                            loadAssignment1);
        final Cluster staticCluster2 = XdsTestResources.createStaticCluster(bootstrapClusterName2,
                                                                            loadAssignment2);
        return Bootstrap
                .newBuilder()
                .setStaticResources(StaticResources.newBuilder()
                                                   .addClusters(staticCluster1)
                                                   .addClusters(staticCluster2))
                .setDynamicResources(
                        DynamicResources.newBuilder()
                                        .setCdsConfig(XdsTestResources.basicConfigSource(bootstrapClusterName1))
                                        .setLdsConfig(XdsTestResources.basicConfigSource(bootstrapClusterName1))
                                        .setAdsConfig(XdsTestResources.apiConfigSource(
                                                bootstrapClusterName2, ApiType.AGGREGATED_GRPC)))
                .build();
    }
}
