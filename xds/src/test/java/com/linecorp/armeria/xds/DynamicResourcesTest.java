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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;
import com.google.protobuf.Message;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * This class ensures that the dynamic bootstrap configuration example at
 * <a href="https://www.envoyproxy.io/docs/envoy/latest/configuration/overview/examples#dynamic">
 * Dynamic Example</a> is parsed and fetched correctly.
 */
class DynamicResourcesTest {

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
        final Listener listener = XdsTestResources.exampleListener("listener_0",
                                                                   "local_route", "bootstrap-cluster");
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(exampleCluster()),
                                ImmutableList.of(exampleEndpoint()),
                                ImmutableList.of(listener),
                                ImmutableList.of(XdsTestResources.exampleRoute("local_route", "some_service")),
                                ImmutableList.of(), "1"));
    }

    @Test
    void basicCase() throws Exception {
        final String listenerName = "listener_0";
        final String routeName = "local_route";
        final String clusterName = "some_service";
        final String bootstrapClusterName = "bootstrap-cluster";
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            xdsBootstrap.startSubscribe(configSource, XdsType.LISTENER, listenerName);

            final TestResourceWatcher<Message> watcher = new TestResourceWatcher<>();
            xdsBootstrap.addListener(XdsType.LISTENER, listenerName, watcher);
            final Listener expectedListener =
                    cache.getSnapshot(GROUP).listeners().resources().get(listenerName);
            awaitAssert(watcher, "onChanged", expectedListener);

            final RouteConfiguration expectedRoute =
                    cache.getSnapshot(GROUP).routes().resources().get(routeName);
            xdsBootstrap.addListener(XdsType.ROUTE, routeName, watcher);
            awaitAssert(watcher, "onChanged", expectedRoute);

            final Cluster expectedCluster =
                    cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            xdsBootstrap.addListener(XdsType.CLUSTER, clusterName, watcher);
            awaitAssert(watcher, "onChanged", expectedCluster);
            final ClusterLoadAssignment expectedEndpoint =
                    cache.getSnapshot(GROUP).endpoints().resources().get(clusterName);
            xdsBootstrap.addListener(XdsType.ENDPOINT, clusterName, watcher);
            awaitAssert(watcher, "onChanged", expectedEndpoint);

            Thread.sleep(100);
            assertThat(watcher.events()).isEmpty();
        }
    }

    static ClusterLoadAssignment exampleEndpoint() {
        return ClusterLoadAssignment
                .newBuilder()
                .setClusterName("some_service")
                .addEndpoints(LocalityLbEndpoints.newBuilder()
                                                 .addLbEndpoints(XdsTestResources.endpoint("127.0.0.1", 1234)))
                .build();
    }

    static Cluster exampleCluster() {
        final ApiConfigSource apiConfigSource = ApiConfigSource
                .newBuilder()
                .setApiType(ApiType.GRPC)
                .setTransportApiVersion(ApiVersion.V3)
                .addGrpcServices(
                        io.envoyproxy.envoy.config.core.v3.GrpcService
                                .newBuilder()
                                .setEnvoyGrpc(EnvoyGrpc.newBuilder()
                                                       .setClusterName("bootstrap-cluster")))
                .build();
        return Cluster
                .newBuilder()
                .setName("some_service")
                .setConnectTimeout(Duration.newBuilder().setNanos(25_000_000))
                .setLbPolicy(LbPolicy.ROUND_ROBIN)
                .setType(DiscoveryType.EDS)
                .setEdsClusterConfig(
                        EdsClusterConfig
                                .newBuilder()
                                .setEdsConfig(ConfigSource
                                                      .newBuilder()
                                                      .setResourceApiVersion(ApiVersion.V3)
                                                      .setApiConfigSource(apiConfigSource)))
                      .build();
    }
}
