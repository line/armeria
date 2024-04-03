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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

class XdsClientCleanupTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment("cluster1", "127.0.0.1", 8080);
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(XdsTestResources.createStaticCluster("cluster1", loadAssignment)),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));
    }

    @Test
    void testRemoveWatcher() throws Exception {
        final String clusterName = "cluster1";
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot(clusterName);
            final Map<ConfigSource, ConfigSourceClient> clientMap = xdsBootstrap.clientMap();
            await().until(() -> !clientMap.isEmpty());

            clusterRoot.close();
            await().untilAsserted(() -> assertThat(clientMap).isEmpty());
        }
    }

    @Test
    void testMultipleWatchers() throws Exception {
        final String clusterName = "cluster1";
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final SafeCloseable closeable1 = xdsBootstrap.clusterRoot(clusterName);
            final SafeCloseable closeable2 = xdsBootstrap.clusterRoot(clusterName);
            final Map<ConfigSource, ConfigSourceClient> clientMap = xdsBootstrap.clientMap();
            await().untilAsserted(() -> assertThat(clientMap).isNotEmpty());

            closeable1.close();
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(clientMap).isNotEmpty());
            closeable2.close();

            await().untilAsserted(() -> assertThat(clientMap).isEmpty());
        }
    }

    @Test
    void closeIsValidOnce() throws Exception {
        final String clusterName = "cluster1";
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final ClusterRoot clusterRoot1 = xdsBootstrap.clusterRoot(clusterName);
            final ClusterRoot clusterRoot2 = xdsBootstrap.clusterRoot(clusterName);
            final Map<ConfigSource, ConfigSourceClient> clientMap = xdsBootstrap.clientMap();
            await().untilAsserted(() -> assertThat(clientMap).isNotEmpty());

            clusterRoot1.close();
            clusterRoot1.close();
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(clientMap).isNotEmpty());

            clusterRoot2.close();
            await().untilAsserted(() -> assertThat(clientMap).isEmpty());
        }
    }
}
