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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

class ApiConfigSourceTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache1 = new SimpleCache<>(node -> GROUP);
    private static final SimpleCache<String> cache2 = new SimpleCache<>(node -> GROUP);

    private static final String bootstrapCluster1 = "bootstrap-cluster1";
    private static final String bootstrapCluster2 = "bootstrap-cluster2";

    private static final String basicClusterName = "basic-cluster";
    private static final String adsClusterName = "ads-cluster";

    @RegisterExtension
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache1);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    static final ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache2);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        final ConfigSource basicConfigSource = XdsTestResources.basicConfigSource(bootstrapCluster2);
        final ConfigSource adsConfigSource = XdsTestResources.adsConfigSource();
        final Cluster adsCluster = XdsTestResources.createCluster(adsClusterName, adsConfigSource);
        final ClusterLoadAssignment adsEndpoint =
                XdsTestResources.loadAssignment(adsClusterName, "basic.com", 8080);
        cache1.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(adsCluster), ImmutableList.of(adsEndpoint),
                                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "1"));
        final Cluster basicCluster = XdsTestResources.createCluster(basicClusterName, basicConfigSource);
        final ClusterLoadAssignment basicEndpoint =
                XdsTestResources.loadAssignment(basicClusterName, "ads.com", 8080);
        cache2.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(basicCluster), ImmutableList.of(basicEndpoint),
                                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @Test
    void testAds() {
        final ApiConfigSource adsConfigSource =
                XdsTestResources.apiConfigSource(bootstrapCluster1, ApiType.AGGREGATED_GRPC);
        final Cluster bootstrap1 =
                XdsTestResources.createStaticCluster(
                        bootstrapCluster1,
                        XdsTestResources.loadAssignment(bootstrapCluster1, server1.httpUri()));
        final Cluster bootstrap2 =
                XdsTestResources.createStaticCluster(
                        bootstrapCluster2,
                        XdsTestResources.loadAssignment(bootstrapCluster2, server2.httpUri()));
        final Bootstrap bootstrap = XdsTestResources.bootstrap(adsConfigSource, null, bootstrap1, bootstrap2);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot root = xdsBootstrap.clusterRoot(adsClusterName);

            final TestResourceWatcher watcher = new TestResourceWatcher();
            root.addSnapshotWatcher(watcher);

            final ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            final Cluster expected =
                    cache1.getSnapshot(GROUP).clusters().resources().get(adsClusterName);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expected);

            final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
            final ClusterLoadAssignment expected2 =
                    cache1.getSnapshot(GROUP).endpoints().resources().get(adsClusterName);
            assertThat(endpointSnapshot.xdsResource().resource()).isEqualTo(expected2);
        }
    }

    @Test
    void testBasic() {
        final ApiConfigSource adsConfigSource =
                XdsTestResources.apiConfigSource(bootstrapCluster1, ApiType.AGGREGATED_GRPC);
        final ConfigSource basicConfigSource = XdsTestResources.basicConfigSource(bootstrapCluster2);
        final Cluster bootstrap1 =
                XdsTestResources.createStaticCluster(
                        bootstrapCluster1,
                        XdsTestResources.loadAssignment(bootstrapCluster1, server1.httpUri()));
        final Cluster bootstrap2 =
                XdsTestResources.createStaticCluster(
                        bootstrapCluster2,
                        XdsTestResources.loadAssignment(bootstrapCluster2, server2.httpUri()));
        final Bootstrap bootstrap = XdsTestResources.bootstrap(adsConfigSource, basicConfigSource,
                                                               bootstrap1, bootstrap2);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot root = xdsBootstrap.clusterRoot(basicClusterName);

            final TestResourceWatcher watcher = new TestResourceWatcher();
            root.addSnapshotWatcher(watcher);
            final ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            final Cluster expected =
                    cache2.getSnapshot(GROUP).clusters().resources().get(basicClusterName);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expected);

            final ClusterLoadAssignment expected2 =
                    cache2.getSnapshot(GROUP).endpoints().resources().get(basicClusterName);
            final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
            assertThat(endpointSnapshot.xdsResource().resource()).isEqualTo(expected2);
        }
    }
}
