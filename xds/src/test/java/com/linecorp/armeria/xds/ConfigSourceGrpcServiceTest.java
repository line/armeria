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
import static com.linecorp.armeria.xds.XdsTestResources.apiConfigSource;
import static com.linecorp.armeria.xds.XdsTestResources.createCluster;
import static com.linecorp.armeria.xds.XdsTestResources.exampleListener;
import static com.linecorp.armeria.xds.XdsTestResources.httpConnectionManager;
import static com.linecorp.armeria.xds.XdsTestResources.loadAssignment;
import static com.linecorp.armeria.xds.XdsTestResources.routeConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.auth.AuthService;
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
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;

class ConfigSourceGrpcServiceTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final String PASSWORD = "PASSWORD";
    private static final String TOKEN = "username:" + PASSWORD;
    private static final HeaderValue HEADER_VALUE =
            HeaderValue.newBuilder()
                       .setKey("Authorization")
                       .setValue("basic " + Base64.getEncoder()
                                                  .encodeToString(TOKEN.getBytes(StandardCharsets.UTF_8)))
                       .build();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
            sb.decorator(AuthService.builder()
                                    .addBasicAuth((ctx, data) -> CompletableFuture
                                            .completedFuture(PASSWORD.equals(data.password())))
                                    .newDecorator());
        }
    };

    @BeforeEach
    void beforeEach() {
        final ApiConfigSource configSource =
                apiConfigSource(BOOTSTRAP_CLUSTER_NAME, ApiType.AGGREGATED_GRPC, HEADER_VALUE);
        final HttpConnectionManager httpConnectionManager =
                httpConnectionManager(Rds.newBuilder()
                                         .setRouteConfigName("route1")
                                         .setConfigSource(ConfigSource.newBuilder()
                                                                      .setApiConfigSource(configSource))
                                         .build());

        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(createCluster("cluster1", 0)),
                        ImmutableList.of(loadAssignment("cluster1", URI.create("http://a.b"))),
                        ImmutableList.of(exampleListener("listener1", httpConnectionManager)),
                        ImmutableList.of(routeConfiguration("route1", "cluster1")),
                        ImmutableList.of(), "1"));
    }

    @Test
    void testHeader() {
        final String listenerName = "listener1";

        final ApiConfigSource adsConfigSource =
                apiConfigSource(BOOTSTRAP_CLUSTER_NAME, ApiType.AGGREGATED_GRPC, HEADER_VALUE);
        final Cluster bootstrap1 =
                XdsTestResources.createStaticCluster(
                        BOOTSTRAP_CLUSTER_NAME, loadAssignment(BOOTSTRAP_CLUSTER_NAME,
                                                                                server.httpUri()));
        final Bootstrap bootstrap = XdsTestResources.bootstrap(adsConfigSource, null, bootstrap1);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(listenerName);
            final TestResourceWatcher watcher = new TestResourceWatcher();
            listenerRoot.addSnapshotWatcher(watcher);
            final ListenerSnapshot listenerSnapshot = watcher.blockingChanged(ListenerSnapshot.class);

            final Listener expectedListener =
                    cache.getSnapshot(GROUP).listeners().resources().get(listenerName);
            assertThat(listenerSnapshot.xdsResource().resource()).isEqualTo(expectedListener);
        }
    }
}
