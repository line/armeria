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

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
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
    private static final AtomicBoolean simulateTimeout = new AtomicBoolean();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.decorator((delegate, ctx, req) -> {
                if (simulateTimeout.get()) {
                    ctx.cancel();
                    return HttpResponse.streaming();
                }
                return delegate.serve(ctx, req);
            });
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
        simulateTimeout.set(false);
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @Test
    void initialTimeoutInvokesAbsent() throws Exception {
        simulateTimeout.set(true);
        final String clusterName = "cluster1";
        final TestResourceWatcher watcher = new TestResourceWatcher();
        final Bootstrap bootstrap = bootstrapWithTimeout(100);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(clusterName);
            clusterRoot.addSnapshotWatcher(watcher);

            assertThat(watcher.blockingMissing()).isEqualTo(ImmutableList.of(XdsType.CLUSTER, clusterName));

            // add the resource afterward
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(TestResources.createCluster(clusterName)),
                                    ImmutableList.of(XdsTestResources.loadAssignment(clusterName, URI.create("http://a.b"))),
                                    ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "2"));
            simulateTimeout.set(false);
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            final ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(watcher.events()).isEmpty());
        }
    }

    private static Bootstrap bootstrapWithTimeout(long timeoutMillis) {
        final Duration timeoutDuration =
                Duration.newBuilder()
                        .setNanos((int) TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
                        .build();
        final ConfigSource configSource =
                ConfigSource.newBuilder()
                            .setApiConfigSource(XdsTestResources.apiConfigSource(BOOTSTRAP_CLUSTER_NAME,
                                                                                 ApiType.GRPC))
                            .setInitialFetchTimeout(timeoutDuration)
                            .build();
        final URI uri = server.httpUri();
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(BOOTSTRAP_CLUSTER_NAME, uri.getHost(), uri.getPort());
        final Cluster cluster = XdsTestResources.createStaticCluster(BOOTSTRAP_CLUSTER_NAME, loadAssignment);
        return XdsTestResources.bootstrap(configSource, cluster);
    }

    @Test
    void noTimeoutIfCachedValue() throws Exception {
        final TestResourceWatcher watcher = new TestResourceWatcher();
        final String clusterName = "cluster1";
        simulateTimeout.set(true);
        final Bootstrap bootstrap = bootstrapWithTimeout(100);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(clusterName);
            clusterRoot.addSnapshotWatcher(watcher);
            assertThat(watcher.blockingMissing()).isEqualTo(ImmutableList.of(XdsType.CLUSTER, clusterName));

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(TestResources.createCluster(clusterName)),
                                    ImmutableList.of(XdsTestResources.loadAssignment(clusterName, URI.create("http://a.b"))),
                                    ImmutableList.of(),
                                    ImmutableList.of(), ImmutableList.of(), "2"));
            simulateTimeout.set(false);
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            ClusterSnapshot clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            // try opening another root to verify the cached value is used
            simulateTimeout.set(true);
            final ClusterRoot clusterRoot2 = xdsBootstrap.clusterRoot(clusterName);
            clusterRoot2.addSnapshotWatcher(watcher);
            clusterSnapshot = watcher.blockingChanged(ClusterSnapshot.class);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expectedCluster);

            // ensure that onAbsent not triggered at the timeout
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(watcher.events()).isEmpty());
        }
    }
}
