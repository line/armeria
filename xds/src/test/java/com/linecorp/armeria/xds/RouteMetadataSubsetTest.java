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

import static com.linecorp.armeria.xds.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;
import static com.linecorp.armeria.xds.XdsConverterUtilTest.sampleClusterLoadAssignment;
import static com.linecorp.armeria.xds.XdsTestResources.stringValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.Struct;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetFallbackPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager.CodecType;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

class RouteMetadataSubsetTest {

    private static final String bootstrapClusterName = "bootstrap-cluster";

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final SimpleCache<String> cache = new SimpleCache<>(node -> "key");
            setupCache(cache);
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }

        void setupCache(SimpleCache<String> cache) {
            final ConfigSource configSource = ConfigSource.newBuilder()
                                                          .setAds(AggregatedConfigSource.getDefaultInstance())
                                                          .setResourceApiVersion(ApiVersion.V3)
                                                          .build();
            final Cluster cluster =
                    Cluster.newBuilder()
                           .setName("cluster")
                           .setEdsClusterConfig(Cluster.EdsClusterConfig
                                                        .newBuilder().setEdsConfig(configSource))
                           .setType(Cluster.DiscoveryType.EDS)
                           .setLbSubsetConfig(
                                   LbSubsetConfig.newBuilder()
                                                 .setFallbackPolicy(LbSubsetFallbackPolicy.ANY_ENDPOINT)
                                                 .addSubsetSelectors(LbSubsetSelector.newBuilder()
                                                                                     .addKeys("foo")
                                                                                     .addKeys("bar")
                                                                                     .build())
                                                 .build())
                           .build();
            final ClusterLoadAssignment loadAssignment = sampleClusterLoadAssignment();
            cache.setSnapshot(
                    "key",
                    Snapshot.create(ImmutableList.of(cluster),
                                    ImmutableList.of(loadAssignment),
                                    ImmutableList.of(),
                                    ImmutableList.of(),
                                    ImmutableList.of(),
                                    "1"));
        }
    };

    @Test
    void routeMetadataMatch() {
        final ConfigSource configSource = XdsTestResources.basicConfigSource(bootstrapClusterName);
        final URI uri = server.httpUri();
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(bootstrapClusterName,
                                                uri.getHost(), uri.getPort());
        final Cluster bootstrapCluster =
                XdsTestResources.createStaticCluster(bootstrapClusterName, loadAssignment);
        final Metadata routeMetadataMatch1 = Metadata.newBuilder().putFilterMetadata(
                SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.newBuilder()
                                                         .putFields("foo", stringValue("foo1"))
                                                         .putFields("bar", stringValue("bar1"))
                                                         .build()).build();
        Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, listener(routeMetadataMatch1),
                                                         bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup xdsEndpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot("listener"));
            await().untilAsserted(() -> assertThat(xdsEndpointGroup.endpoints())
                    .containsExactly(Endpoint.of("127.0.0.1", 8082)));
        }

        // No metadata. Fallback to all endpoints.
        final Metadata routeMetadataMatch2 = Metadata.newBuilder().putFilterMetadata(
                SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.getDefaultInstance()).build();
        bootstrap = XdsTestResources.bootstrap(configSource, listener(routeMetadataMatch2),
                                               bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup xdsEndpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot("listener"));
            await().untilAsserted(() -> assertThat(xdsEndpointGroup.endpoints())
                    .containsExactlyInAnyOrder(Endpoint.of("127.0.0.1", 8080), Endpoint.of("127.0.0.1", 8081),
                                               Endpoint.of("127.0.0.1", 8082)));
        }

        // No matched metadata. Fallback to all endpoints.
        final Metadata routeMetadataMatch3 = Metadata.newBuilder().putFilterMetadata(
                SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.newBuilder()
                                                         .putFields("foo", stringValue("foo1"))
                                                         .build()).build();
        bootstrap = XdsTestResources.bootstrap(configSource, listener(routeMetadataMatch3),
                                               bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup xdsEndpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot("listener"));
            await().untilAsserted(() -> assertThat(xdsEndpointGroup.endpoints())
                    .containsExactlyInAnyOrder(Endpoint.of("127.0.0.1", 8080), Endpoint.of("127.0.0.1", 8081),
                                               Endpoint.of("127.0.0.1", 8082)));
        }
    }

    private static Listener listener(Metadata metadata) {
        final VirtualHost virtualHost =
                VirtualHost.newBuilder()
                           .setName("route")
                           .addDomains("*")
                           .addRoutes(Route.newBuilder()
                                           .setMatch(RouteMatch.newBuilder().setPrefix("/"))
                                           .setRoute(RouteAction.newBuilder()
                                                                .setMetadataMatch(metadata)
                                                                .setCluster("cluster")))
                           .build();
        final HttpConnectionManager manager =
                HttpConnectionManager
                        .newBuilder()
                        .setCodecType(CodecType.AUTO)
                        .setStatPrefix("ingress_http")
                        .setRouteConfig(RouteConfiguration.newBuilder()
                                                          .setName("route")
                                                          .addVirtualHosts(virtualHost)
                                                          .build())
                        .addHttpFilters(HttpFilter.newBuilder()
                                                  .setName("envoy.filters.http.router")
                                                  .setTypedConfig(Any.pack(Router.getDefaultInstance())))
                        .build();
        return Listener.newBuilder()
               .setName("listener")
               .setApiListener(ApiListener.newBuilder().setApiListener(Any.pack(manager)))
               .build();
    }
}
