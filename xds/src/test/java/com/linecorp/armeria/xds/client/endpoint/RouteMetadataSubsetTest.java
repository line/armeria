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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.XdsTestResources.BOOTSTRAP_CLUSTER_NAME;
import static com.linecorp.armeria.xds.XdsTestResources.bootstrapCluster;
import static com.linecorp.armeria.xds.XdsTestResources.staticResourceListener;
import static com.linecorp.armeria.xds.XdsTestResources.stringValue;
import static com.linecorp.armeria.xds.client.endpoint.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;
import static com.linecorp.armeria.xds.client.endpoint.XdsConverterUtilTest.sampleClusterLoadAssignment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Struct;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsTestResources;

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

class RouteMetadataSubsetTest {

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
            final String anyEndpointFallbackClusterName = "any_endpoint_fallback_cluster";
            final Cluster anyEndpointFallbackCluster =
                    Cluster.newBuilder()
                           .setName(anyEndpointFallbackClusterName)
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
            final ClusterLoadAssignment anyEndpointLoadAssignment =
                    sampleClusterLoadAssignment(anyEndpointFallbackClusterName);
            final String noFallbackClusterName = "no_fallback_cluster";
            final Cluster noFallbackCluster =
                    Cluster.newBuilder()
                           .setName(noFallbackClusterName)
                           .setEdsClusterConfig(Cluster.EdsClusterConfig
                                                        .newBuilder().setEdsConfig(configSource))
                           .setType(Cluster.DiscoveryType.EDS)
                           .setLbSubsetConfig(
                                   LbSubsetConfig.newBuilder()
                                                 .setFallbackPolicy(LbSubsetFallbackPolicy.NO_FALLBACK)
                                                 .addSubsetSelectors(LbSubsetSelector.newBuilder()
                                                                                     .addKeys("foo")
                                                                                     .addKeys("bar")
                                                                                     .build())
                                                 .build())
                           .build();
            final ClusterLoadAssignment noFallbackLoadAssignment =
                    sampleClusterLoadAssignment(noFallbackClusterName);
            cache.setSnapshot(
                    "key",
                    Snapshot.create(ImmutableList.of(anyEndpointFallbackCluster, noFallbackCluster),
                                    ImmutableList.of(anyEndpointLoadAssignment, noFallbackLoadAssignment),
                                    ImmutableList.of(),
                                    ImmutableList.of(),
                                    ImmutableList.of(),
                                    "1"));
        }
    };

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void routeMetadataMatch(boolean noFallback) {
        final String clusterName = noFallback ? "no_fallback_cluster" : "any_endpoint_fallback_cluster";

        final ConfigSource configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME);
        final Cluster bootstrapCluster = bootstrapCluster(server.httpUri(), BOOTSTRAP_CLUSTER_NAME);
        final Metadata routeMetadataMatch1 = Metadata.newBuilder().putFilterMetadata(
                SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.newBuilder()
                                                         .putFields("foo", stringValue("foo1"))
                                                         .putFields("bar", stringValue("bar1"))
                                                         .build()).build();
        Bootstrap bootstrap = XdsTestResources.bootstrap(
                configSource, staticResourceListener(routeMetadataMatch1, clusterName),
                bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot("listener"));

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
        }

        // No Route metadata so use all endpoints.
        final Metadata routeMetadataMatch2 = Metadata.newBuilder().putFilterMetadata(
                SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.getDefaultInstance()).build();
        bootstrap = XdsTestResources.bootstrap(configSource,
                                               staticResourceListener(routeMetadataMatch2, clusterName),
                                               bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot("listener"));

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
        }

        final Metadata routeMetadataMatch3 = Metadata.newBuilder().putFilterMetadata(
                SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.newBuilder()
                                                         .putFields("foo", stringValue("foo1"))
                                                         .build()).build();
        bootstrap = XdsTestResources.bootstrap(configSource,
                                               staticResourceListener(routeMetadataMatch3, clusterName),
                                               bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot("listener"));

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            if (noFallback) {
                assertThat(endpointGroup.selectNow(ctx)).isNull();
            } else {
                assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
                assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
                assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
            }
        }
    }
}
