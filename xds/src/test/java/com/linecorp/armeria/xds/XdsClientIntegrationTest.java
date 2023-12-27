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

import static com.linecorp.armeria.xds.XdsTestUtil.awaitAssert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;

public class XdsClientIntegrationTest {

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
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));
    }

    @Test
    void basicCase() throws Exception {
        final String bootstrapClusterName = "bootstrap-cluster";
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final String clusterName = "cluster1";
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final SafeCloseable watcherCloseable = xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER,
                                                                         clusterName);
            final TestResourceWatcher<Cluster> watcher = new TestResourceWatcher<>();
            xdsBootstrap.addListener(XdsType.CLUSTER, clusterName, watcher);

            // Updates are propagated for the initial value
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            awaitAssert(watcher, "onChanged", expectedCluster);

            // Updates are propagated if the cache is updated
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster(clusterName, 1)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));
            final Cluster expectedCluster2 = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            awaitAssert(watcher, "onChanged", expectedCluster2);

            // Updates aren't propagated after the watch is removed
            watcherCloseable.close();
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster(clusterName, 2)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));

            Thread.sleep(100);
            assertThat(watcher.first("onChanged")).isEmpty();
        }
    }

    @Test
    void multipleResources() throws Exception {
        final String bootstrapClusterName = "bootstrap-cluster";
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final TestResourceWatcher<Cluster> watcher = new TestResourceWatcher<>();
            final SafeCloseable closeCluster1 =
                    xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER, "cluster1");
            xdsBootstrap.addListener(XdsType.CLUSTER, "cluster1", watcher);

            // Updates are propagated for the initial value
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            awaitAssert(watcher, "onChanged", expectedCluster);

            // Updates are propagated if the cache is updated
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster("cluster1", 1),
                                             XdsTestResources.createCluster("cluster2", 1)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));
            final Cluster expectedCluster2 = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            awaitAssert(watcher, "onChanged", expectedCluster2);

            xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER, "cluster2");
            xdsBootstrap.addListener(XdsType.CLUSTER, "cluster2", watcher);
            final Cluster expectedCluster3 = cache.getSnapshot(GROUP).clusters().resources().get("cluster2");
            awaitAssert(watcher, "onChanged", expectedCluster3);

            // try removing the watcher for cluster1
            closeCluster1.close();
            awaitAssert(watcher, "onResourceDoesNotExist", "cluster1");

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster("cluster1", 2),
                                             XdsTestResources.createCluster("cluster2", 2)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "3"));
            final Cluster expectedCluster4 = cache.getSnapshot(GROUP).clusters().resources().get("cluster2");
            awaitAssert(watcher, "onChanged", expectedCluster4);

            Thread.sleep(100);
            assertThat(watcher.events()).isEmpty();
        }
    }

    @Test
    void initialValue() throws Exception {
        final String bootstrapClusterName = "bootstrap-cluster";
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final TestResourceWatcher<Cluster> watcher =
                    new TestResourceWatcher<>();
            xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER, "cluster1");
            xdsBootstrap.addListener(XdsType.CLUSTER, "cluster1", watcher);

            // Updates are propagated for the initial value
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            awaitAssert(watcher, "onChanged", expectedCluster);

            // add another watcher and check that the event is propagated immediately
            xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER, "cluster1");
            xdsBootstrap.addListener(XdsType.CLUSTER, "cluster1", watcher);
            awaitAssert(watcher, "onChanged", expectedCluster);

            Thread.sleep(100);
            assertThat(watcher.events()).hasSize(0);
        }
    }

    @Test
    void errorHandling() throws Exception {
        final String bootstrapClusterName = "bootstrap-cluster";
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final TestResourceWatcher<Cluster> watcher = new TestResourceWatcher<>();
            xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER, "cluster1");
            xdsBootstrap.addListener(XdsType.CLUSTER, "cluster1", watcher);

            // Updates are propagated for the initial value
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            awaitAssert(watcher, "onChanged", expectedCluster);

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
            await().until(() -> watcher.eventSize() >= 1);
            final Cluster expectedCluster2 = cache.getSnapshot(GROUP).clusters().resources().get("cluster1");
            awaitAssert(watcher, "onChanged", expectedCluster2);

            Thread.sleep(100);
            assertThat(watcher.eventSize()).isEqualTo(0);
        }
    }
}
