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

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

class ClientTimeoutTest {

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
                Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @Test
    void initialTimeoutInvokesAbsent() throws Exception {
        final TestResourceWatcher<Cluster> watcher = new TestResourceWatcher<>();
        final String bootstrapClusterName = "bootstrap-cluster";
        final String clusterName = "cluster1";
        final Duration timeoutDuration =
                Duration.newBuilder()
                        .setNanos((int) TimeUnit.MILLISECONDS.toNanos(100))
                        .build();
        final ConfigSource configSource =
                ConfigSource.newBuilder()
                            .setApiConfigSource(XdsTestResources.apiConfigSource(bootstrapClusterName,
                                                                                 ApiType.GRPC))
                            .setInitialFetchTimeout(timeoutDuration)
                            .build();
        final URI uri = server.httpUri();
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(bootstrapClusterName, uri.getHost(), uri.getPort());
        final Cluster cluster = XdsTestResources.createStaticCluster(bootstrapClusterName, loadAssignment);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, cluster);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            xdsBootstrap.startSubscribe(null, XdsType.CLUSTER, clusterName);
            xdsBootstrap.addListener(XdsType.CLUSTER, clusterName, watcher);

            await().untilAsserted(
                    () -> assertThat(watcher.first("onResourceDoesNotExist")).hasValue(clusterName));
            watcher.popFirst();

            // add the resource afterward
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(TestResources.createCluster(clusterName)),
                                    ImmutableList.of(), ImmutableList.of(),
                                    ImmutableList.of(), ImmutableList.of(), "2"));
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            awaitAssert(watcher, "onChanged", expectedCluster);

            Thread.sleep(100);
            await().until(() -> watcher.eventSize() == 0);
        }
    }

    @Test
    void noTimeoutIfCachedValue() throws Exception {
        final TestResourceWatcher<Cluster> watcher = new TestResourceWatcher<>();
        final String bootstrapClusterName = "bootstrap-cluster";
        final String clusterName = "cluster1";
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            xdsBootstrap.startSubscribe(configSource, XdsType.CLUSTER, clusterName);
            xdsBootstrap.addListener(XdsType.CLUSTER, clusterName, watcher);

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(TestResources.createCluster(clusterName)),
                                    ImmutableList.of(), ImmutableList.of(),
                                    ImmutableList.of(), ImmutableList.of(), "2"));
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            awaitAssert(watcher, "onChanged", expectedCluster);

            // ensure that onAbsent not triggered at the timeout
            Thread.sleep(100);
            await().until(() -> watcher.eventSize() == 0);
        }
    }
}
