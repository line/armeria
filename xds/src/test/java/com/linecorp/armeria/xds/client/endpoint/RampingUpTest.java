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

import static com.linecorp.armeria.internal.client.endpoint.RampingUpKeys.createdAtNanos;
import static com.linecorp.armeria.xds.XdsTestResources.BOOTSTRAP_CLUSTER_NAME;
import static com.linecorp.armeria.xds.XdsTestResources.endpoint;
import static com.linecorp.armeria.xds.XdsTestResources.localityLbEndpoints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
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
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RoundRobinLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.SlowStartConfig;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

class RampingUpTest {

    private static final String GROUP = "key";
    private static final String listenerName = "listener";
    private static final String routeName = "route";
    private static final String clusterName = "cluster";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    @BeforeEach
    void beforeEach() {
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(), ImmutableList.of(),
                                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @Test
    void checkEndpointsAreRampedUp() throws Exception {
        // set a large window to verify the first step of ramping up is set
        final int windowMillis = 1000;
        final int weight = 10;
        final Cluster cluster = slowStartCluster(windowMillis);
        LocalityLbEndpoints localityLbEndpoints =
                localityLbEndpoints(Locality.getDefaultInstance(),
                                    ImmutableList.of(endpoint("a.com", 80, weight),
                                                     endpoint("b.com", 80, weight)));
        ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(clusterName)
                                .toBuilder()
                                .addEndpoints(localityLbEndpoints).build();
        final Listener listener =
                XdsTestResources.exampleListener(listenerName, routeName);
        final RouteConfiguration route = XdsTestResources.routeConfiguration(routeName, clusterName);
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(loadAssignment),
                                ImmutableList.of(listener), ImmutableList.of(route), ImmutableList.of(), "2"));

        final ConfigSource configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME);
        final URI uri = server.httpUri();
        final ClusterLoadAssignment bootstrapLoadAssignment =
                XdsTestResources.loadAssignment(BOOTSTRAP_CLUSTER_NAME, uri.getHost(), uri.getPort());
        final Cluster bootstrapCluster =
                XdsTestResources.createStaticCluster(BOOTSTRAP_CLUSTER_NAME, bootstrapLoadAssignment);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup xdsEndpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot(listenerName));
            await().untilAsserted(() -> assertThat(xdsEndpointGroup.endpoints())
                    .containsExactlyInAnyOrder(Endpoint.of("a.com", 80), Endpoint.of("b.com", 80)));

            Set<Endpoint> selectedEndpoints = selectEndpoints(weight, xdsEndpointGroup);
            assertThat(selectedEndpoints)
                    .containsExactlyInAnyOrder(Endpoint.of("a.com", 80), Endpoint.of("b.com", 80));
            final Endpoint aEndpoint = filterEndpoint(selectedEndpoints, "a.com");
            Endpoint bEndpoint = filterEndpoint(selectedEndpoints, "b.com");
            assertThat(createdAtNanos(aEndpoint)).isEqualTo(createdAtNanos(bEndpoint));
            assertThat(aEndpoint.weight()).isLessThan(weight);
            assertThat(bEndpoint.weight()).isLessThan(weight);

            // wait until ramp up is complete
            Thread.sleep(windowMillis);

            // set new endpoints
            localityLbEndpoints =
                    localityLbEndpoints(Locality.getDefaultInstance(),
                                        ImmutableList.of(endpoint("b.com", 80, weight),
                                                         endpoint("c.com", 80, weight)));
            loadAssignment =
                    XdsTestResources.loadAssignment(clusterName)
                                    .toBuilder()
                                    .addEndpoints(localityLbEndpoints).build();
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(loadAssignment),
                                    ImmutableList.of(listener), ImmutableList.of(route),
                                    ImmutableList.of(), "3"));
            await().untilAsserted(() -> assertThat(xdsEndpointGroup.endpoints())
                    .containsExactlyInAnyOrder(Endpoint.of("b.com", 80), Endpoint.of("c.com", 80)));
            selectedEndpoints = selectEndpoints(weight, xdsEndpointGroup);
            bEndpoint = filterEndpoint(selectedEndpoints, "b.com");
            final Endpoint cEndpoint = filterEndpoint(selectedEndpoints, "c.com");
            assertThat(createdAtNanos(bEndpoint)).isLessThan(createdAtNanos(cEndpoint));
            assertThat(bEndpoint.weight()).isEqualTo(weight);
            assertThat(cEndpoint.weight()).isLessThan(weight);
        }
    }

    /**
     * WeightedRandomDistributionSelector is random, so we just call selectNow
     * for a full iteration to consume all pending entries.
     */
    private static Set<Endpoint> selectEndpoints(int weight, EndpointGroup xdsEndpointGroup) {
        final Set<Endpoint> selectedEndpoints = new HashSet<>();
        for (int i = 0; i < weight * 2; i++) {
            selectedEndpoints.add(xdsEndpointGroup.select(ctx(), CommonPools.workerGroup()).join());
            selectedEndpoints.add(xdsEndpointGroup.select(ctx(), CommonPools.workerGroup()).join());
        }
        return selectedEndpoints;
    }

    @Test
    void basicCallGoesThrough() {
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(clusterName, server.httpUri());
        final Listener listener = XdsTestResources.exampleListener(listenerName, routeName);
        final RouteConfiguration route = XdsTestResources.routeConfiguration(routeName, clusterName);
        cache.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(slowStartCluster(1000)), ImmutableList.of(loadAssignment),
                                ImmutableList.of(listener), ImmutableList.of(route), ImmutableList.of(), "2"));

        final ConfigSource configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME);
        final URI uri = server.httpUri();
        final ClusterLoadAssignment bootstrapLoadAssignment =
                XdsTestResources.loadAssignment(BOOTSTRAP_CLUSTER_NAME, uri.getHost(), uri.getPort());
        final Cluster bootstrapCluster =
                XdsTestResources.createStaticCluster(BOOTSTRAP_CLUSTER_NAME, bootstrapLoadAssignment);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup xdsEndpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot(listenerName));
            final BlockingWebClient blockingClient = WebClient.of(SessionProtocol.HTTP, xdsEndpointGroup)
                                                              .blocking();
            assertThat(blockingClient.get("/hello").contentUtf8()).isEqualTo("world");
        }
    }

    private static Endpoint filterEndpoint(Collection<Endpoint> endpoints, String hostName) {
        return endpoints.stream().filter(endpoint -> hostName.equals(endpoint.host()))
                        .findFirst().orElseThrow(() -> new RuntimeException("not found"));
    }

    private static Cluster slowStartCluster(int windowMillis) {
        final Duration window = Duration.newBuilder()
                                        .setNanos((int) TimeUnit.MILLISECONDS.toNanos(windowMillis))
                                        .build();
        final SlowStartConfig slowStartConfig =
                SlowStartConfig.newBuilder()
                               .setSlowStartWindow(window)
                               .build();
        return XdsTestResources.createCluster(clusterName, 0)
                               .toBuilder()
                               .setLbPolicy(LbPolicy.ROUND_ROBIN)
                               .setRoundRobinLbConfig(RoundRobinLbConfig.newBuilder()
                                                                        .setSlowStartConfig(slowStartConfig))
                               .build();
    }

    private static ClientRequestContext ctx() {
        return ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }
}
