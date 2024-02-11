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

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;

class XdsClientIntegrationTest {

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
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(XdsTestResources.createCluster("cluster1", 0)),
                        ImmutableList.of(XdsTestResources.loadAssignment("cluster1", URI.create("http://a.b"))),
                        ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @Test
    void basicCase() throws Exception {
        final String clusterName = "cluster1";
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(clusterName);
            final TestResourceWatcher watcher = new TestResourceWatcher();
            clusterRoot.addSnapshotWatcher(watcher);
            ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);

            // Updates are propagated for the initial value
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            // Updates are propagated if the cache is updated
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster(clusterName, 1)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));
            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            final Cluster expectedCluster2 = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster2);

            // Updates aren't propagated after the watch is removed
            clusterRoot.close();
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster(clusterName, 2)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(watcher.events()).isEmpty());
        }
    }

    @Test
    void multipleResources() throws Exception {
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final TestResourceWatcher watcher = new TestResourceWatcher();
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot("cluster1");
            clusterRoot.addSnapshotWatcher(watcher);
            ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);

            // Updates are propagated for the initial value
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            // Updates are propagated if the cache is updated
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster("cluster1", 1),
                                             XdsTestResources.createCluster("cluster2", 1)),
                            ImmutableList.of(XdsTestResources.loadAssignment("cluster1", URI.create("http://a.b")),
                                             XdsTestResources.loadAssignment("cluster2", URI.create("http://c.d"))),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "2"));
            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            final Cluster expectedCluster2 = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster2);

            final ClusterRoot clusterRoot2 = xdsBootstrap.clusterRoot("cluster2");
            clusterRoot2.addSnapshotWatcher(watcher);
            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            final Cluster expectedCluster3 = cache.getSnapshot(GROUP).clusters().resources().get("cluster2");
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster3);

            // try removing the watcher for cluster1
            clusterRoot.close();
            await().untilAsserted(() -> assertThat(clusterRoot.closed()).isTrue());

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster("cluster1", 2),
                                             XdsTestResources.createCluster("cluster2", 2)),
                            ImmutableList.of(XdsTestResources.loadAssignment("cluster1", URI.create("http://a.b")),
                                             XdsTestResources.loadAssignment("cluster2", URI.create("http://c.d"))),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "3"));
            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            final Cluster expectedCluster4 = cache.getSnapshot(GROUP).clusters().resources().get("cluster2");
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster4);

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(watcher.events()).isEmpty());
        }
    }

    @Test
    void initialValue() throws Exception {
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final TestResourceWatcher watcher = new TestResourceWatcher();
            final ClusterRoot clusterRoot1 = xdsBootstrap.clusterRoot("cluster1");
            clusterRoot1.addSnapshotWatcher(watcher);

            // Updates are propagated for the initial value
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            // add another watcher and check that the event is propagated immediately
            final ClusterRoot clusterRoot2 = xdsBootstrap.clusterRoot("cluster1");
            clusterRoot2.addSnapshotWatcher(watcher);
            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(watcher.events()).isEmpty());
        }
    }

    @Test
    void errorHandling() throws Exception {
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final TestResourceWatcher watcher = new TestResourceWatcher();
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot("cluster1");
            clusterRoot.addSnapshotWatcher(watcher);

            ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);

            // Updates are propagated for the initial value
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            // abort the current connection
            final ServiceRequestContextCaptor captor = server.requestContextCaptor();
            assertThat(captor.isEmpty()).isFalse();
            captor.poll().cancel();

            // update the cache and check if the update is still pushed
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster("cluster1", 1)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));
            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            final Cluster expectedCluster2 = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster2);

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(watcher.events()).isEmpty());
        }
    }
}
