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
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

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
                Snapshot.create(ImmutableList.of(XdsTestResources.createCluster("cluster1"),
                                                 TestResources.createCluster("cluster2", "127.0.0.3",
                                                                             8083, DiscoveryType.STATIC)),
                                ImmutableList.of(clusterLoadAssignment),
                                ImmutableList.of(),
                                ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @Test
    void edsToEds() {
        final String resourceName = "cluster1";
        final String bootstrapClusterName = "bootstrap-cluster";
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final TestResourceWatcher<Cluster> clusterWatcher = new TestResourceWatcher<>();
            xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER, resourceName);
            xdsBootstrap.addListener(XdsType.CLUSTER, resourceName, clusterWatcher);
            // the initial endpoint is fetched
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(resourceName);
            awaitAssert(clusterWatcher, "onChanged", expectedCluster);

            final TestResourceWatcher<ClusterLoadAssignment> endpointsWatcher = new TestResourceWatcher<>();
            xdsBootstrap.addListener(XdsType.ENDPOINT, resourceName, endpointsWatcher);
            final ClusterLoadAssignment expectedEndpoints = cache.getSnapshot(GROUP)
                                                                 .endpoints()
                                                                 .resources()
                                                                 .get(resourceName);
            awaitAssert(endpointsWatcher, "onChanged", expectedEndpoints);

            // update the cache
            final ClusterLoadAssignment assignment =
                    XdsTestResources.loadAssignment(resourceName, "127.0.0.1", 8081);
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(TestResources.createCluster(resourceName)),
                                    ImmutableList.of(assignment),
                                    ImmutableList.of(),
                                    ImmutableList.of(), ImmutableList.of(), "2"));

            // check that the cluster reflects the changed cache
            final ClusterLoadAssignment expectedEndpoints2 = cache.getSnapshot(GROUP)
                                                                  .endpoints()
                                                                  .resources()
                                                                  .get(resourceName);
            awaitAssert(endpointsWatcher, "onChanged", expectedEndpoints2);
        }
    }

    @Test
    void edsToStatic() {
        final String resourceName = "cluster2";
        final String bootstrapClusterName = "bootstrap-cluster";
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER, resourceName);
            final TestResourceWatcher<Cluster> clusterWatcher = new TestResourceWatcher<>();
            xdsBootstrap.addListener(XdsType.CLUSTER, resourceName, clusterWatcher);
            // the initial endpoint is fetched
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(resourceName);
            awaitAssert(clusterWatcher, "onChanged", expectedCluster);

            final ClusterLoadAssignment expected = expectedCluster.getLoadAssignment();
            final TestResourceWatcher<ClusterLoadAssignment> endpointWatcher = new TestResourceWatcher<>();
            xdsBootstrap.addListener(XdsType.ENDPOINT, resourceName, endpointWatcher);
            awaitAssert(endpointWatcher, "onChanged", expected);

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

            // check that the cluster reflects the changed cache
            final ClusterLoadAssignment expected2 = cache.getSnapshot(GROUP)
                                                         .clusters().resources().get(resourceName)
                                                         .getLoadAssignment();
            awaitAssert(endpointWatcher, "onChanged", expected2);
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
        final String bootstrapClusterName = "bootstrap-cluster";
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER, resourceName);
            final TestResourceWatcher<Cluster> clusterWatcher = new TestResourceWatcher<>();
            xdsBootstrap.addListener(XdsType.CLUSTER, resourceName, clusterWatcher);
            // the initial endpoint is fetched
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(resourceName);
            awaitAssert(clusterWatcher, "onChanged", expectedCluster);

            final ClusterLoadAssignment expected = expectedCluster.getLoadAssignment();
            final TestResourceWatcher<ClusterLoadAssignment> endpointWatcher = new TestResourceWatcher<>();
            xdsBootstrap.addListener(XdsType.ENDPOINT, resourceName, endpointWatcher);
            awaitAssert(endpointWatcher, "onChanged", expected);

            // update the cache
            final Cluster cluster = TestResources.createCluster(resourceName, "127.0.0.2",
                                                                8082, DiscoveryType.STATIC);
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(), ImmutableList.of(),
                                    ImmutableList.of(), ImmutableList.of(), "2"));

            // check that the cluster reflects the changed cache
            final ClusterLoadAssignment expected2 = cache.getSnapshot(GROUP)
                                                         .clusters().resources().get(resourceName)
                                                         .getLoadAssignment();
            awaitAssert(endpointWatcher, "onChanged", expected2);
        }
    }
}
