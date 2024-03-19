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
import static com.linecorp.armeria.xds.XdsTestResources.bootstrapCluster;
import static com.linecorp.armeria.xds.XdsTestResources.createCluster;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class ResourceWatcherTest {

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
        final ClusterLoadAssignment clusterLoadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .setClusterName("cluster1")
                        .addEndpoints(
                                LocalityLbEndpoints.newBuilder()
                                                   .addLbEndpoints(XdsTestResources.endpoint("127.0.0.1", 8081))
                                                   .addLbEndpoints(XdsTestResources.endpoint("127.0.0.2", 8082))
                                                   .build())
                        .build();

        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(createCluster("cluster1"),
                                                 TestResources.createCluster("cluster2", "127.0.0.3",
                                                                             8083, DiscoveryType.STATIC)),
                                ImmutableList.of(clusterLoadAssignment),
                                ImmutableList.of(),
                                ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void edsToEds(boolean useStaticCluster) {
        final String resourceName = "cluster1";
        final Bootstrap bootstrap;
        final Cluster expectedCluster;
        if (useStaticCluster) {
            final ConfigSource configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME);
            final Cluster bootstrapCluster = bootstrapCluster(server.httpUri(), BOOTSTRAP_CLUSTER_NAME);
            expectedCluster = createCluster(resourceName);
            bootstrap = XdsTestResources.bootstrap(configSource, Listener.getDefaultInstance(),
                                                   bootstrapCluster, expectedCluster);
        } else {
            expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(resourceName);
            bootstrap = XdsTestResources.bootstrap(server.httpUri());
        }
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final TestResourceWatcher watcher = new TestResourceWatcher();
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(resourceName);
            clusterRoot.addSnapshotWatcher(watcher);

            ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            // the initial endpoint is fetched
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            final ClusterLoadAssignment expectedEndpoints = cache.getSnapshot(GROUP)
                                                                 .endpoints()
                                                                 .resources()
                                                                 .get(resourceName);
            assertThat(clusterSnapshot.endpointSnapshot().xdsResource().resource())
                    .isEqualTo(expectedEndpoints);

            // update the cache
            final ClusterLoadAssignment assignment =
                    XdsTestResources.loadAssignment(resourceName, "127.0.0.1", 8081);
            final List<Cluster> clusters = useStaticCluster ? ImmutableList.of()
                                                            : ImmutableList.of(expectedCluster);
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(clusters,
                                    ImmutableList.of(assignment),
                                    ImmutableList.of(),
                                    ImmutableList.of(), ImmutableList.of(), "2"));
            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);

            // check that the cluster reflects the changed cache
            final ClusterLoadAssignment expectedEndpoints2 = cache.getSnapshot(GROUP)
                                                                  .endpoints()
                                                                  .resources()
                                                                  .get(resourceName);
            assertThat(clusterSnapshot.endpointSnapshot().xdsResource().resource())
                    .isEqualTo(expectedEndpoints2);
        }
    }

    @Test
    void edsToStatic() {
        final String resourceName = "cluster2";
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(resourceName);
            final TestResourceWatcher watcher = new TestResourceWatcher();
            clusterRoot.addSnapshotWatcher(watcher);
            ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            // the initial endpoint is fetched
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(resourceName);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            final ClusterLoadAssignment expected = expectedCluster.getLoadAssignment();
            assertThat(clusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(expected);

            // update the cache
            final Cluster cluster1 = TestResources.createCluster("cluster1");
            final Cluster cluster2 = TestResources.createCluster("cluster2", "127.0.0.4",
                                                                 8084, DiscoveryType.STATIC);
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(cluster1, cluster2),
                                    ImmutableList.of(),
                                    ImmutableList.of(),
                                    ImmutableList.of(), ImmutableList.of(), "2"));

            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            // check that the cluster reflects the changed cache
            final ClusterLoadAssignment expected2 = cache.getSnapshot(GROUP)
                                                         .clusters().resources().get(resourceName)
                                                         .getLoadAssignment();
            assertThat(clusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(expected2);
        }
    }

    @Test
    void staticToStatic() {
        final String resourceName = "cluster1";
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(TestResources.createCluster(resourceName, "127.0.0.1",
                                                                             8081, DiscoveryType.STATIC)),
                                ImmutableList.of(),
                                ImmutableList.of(),
                                ImmutableList.of(), ImmutableList.of(), "1"));
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(resourceName);
            final TestResourceWatcher watcher = new TestResourceWatcher();
            clusterRoot.addSnapshotWatcher(watcher);

            ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            // the initial endpoint is fetched
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(resourceName);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            final ClusterLoadAssignment expected = expectedCluster.getLoadAssignment();
            assertThat(clusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(expected);

            // update the cache
            final Cluster cluster = TestResources.createCluster(resourceName, "127.0.0.2",
                                                                8082, DiscoveryType.STATIC);
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(), ImmutableList.of(),
                                    ImmutableList.of(), ImmutableList.of(), "2"));
            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);

            // check that the cluster reflects the changed cache
            final ClusterLoadAssignment expected2 = cache.getSnapshot(GROUP)
                                                         .clusters().resources().get(resourceName)
                                                         .getLoadAssignment();
            assertThat(clusterSnapshot.endpointSnapshot().xdsResource().resource()).isEqualTo(expected2);
        }
    }
}
