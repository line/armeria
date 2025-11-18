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

import static com.linecorp.armeria.xds.XdsTestResources.createStaticCluster;
import static com.linecorp.armeria.xds.XdsTestResources.endpoint;
import static com.linecorp.armeria.xds.XdsTestResources.loadAssignment;
import static com.linecorp.armeria.xds.XdsTestResources.locality;
import static com.linecorp.armeria.xds.XdsTestResources.percent;
import static com.linecorp.armeria.xds.XdsTestResources.staticBootstrap;
import static com.linecorp.armeria.xds.XdsTestResources.staticResourceListener;
import static com.linecorp.armeria.xds.XdsTestUtil.pollLoadBalancer;
import static io.envoyproxy.envoy.config.core.v3.HealthStatus.HEALTHY;
import static io.envoyproxy.envoy.config.core.v3.HealthStatus.UNHEALTHY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsTestResources;
import com.linecorp.armeria.xds.client.endpoint.XdsRandom.RandomHint;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.ClusterManager;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig.ZoneAwareLbConfig;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class ZoneAwareTest {

    @ParameterizedTest
    @CsvSource(value = { "3:2:2:3", "3:3:3:3" }, delimiter = ':')
    void pickLocalRegion(int upstreamHealthyHosts, int upstreamAHealthyHosts,
                         int localHealthyHosts, int localAHealthyHosts) throws Exception {
        final Listener listener = staticResourceListener();
        final ClusterLoadAssignment loadAssignment =
                loadAssignment("cluster",
                               localityLbEndpoints("local", upstreamHealthyHosts),
                               localityLbEndpoints("regionA", upstreamAHealthyHosts));
        final ZoneAwareLbConfig zoneAwareLbConfig =
                ZoneAwareLbConfig.newBuilder().setMinClusterSize(UInt64Value.of(1)).build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setZoneAwareLbConfig(zoneAwareLbConfig)
                                                             .setHealthyPanicThreshold(percent(0))).build();

        final ClusterLoadAssignment localLoadAssignment =
                loadAssignment("local-cluster",
                               localityLbEndpoints("local", localHealthyHosts),
                               localityLbEndpoints("regionA", localAHealthyHosts));
        final Cluster localCluster = createStaticCluster("local-cluster", localLoadAssignment);

        final Bootstrap bootstrap = staticBootstrap(listener, cluster, localCluster)
                .toBuilder()
                .setNode(Node.newBuilder().setLocality(locality("local")))
                .setClusterManager(ClusterManager.newBuilder().setLocalClusterName("local-cluster"))
                .build();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final SettableXdsRandom random = new SettableXdsRandom();
            ctx.setAttr(XdsAttributeKeys.XDS_RANDOM, random);
            for (int i = 0; i < 10; i++) {
                random.fixNextInt(RandomHint.SELECT_PRIORITY, i * 10);
                random.fixNextLong(RandomHint.LOCAL_PERCENTAGE, i * 1000);

                final Endpoint selected = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
                assertThat(selected).isNotNull();
                final Locality locality = selected.attr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY)
                                                  .getLocality();
                assertThat(locality.getRegion()).isEqualTo("local");
            }
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0:local,regionA", "49:local,regionA", "50:local,regionA", "51:local", "100:local"
    }, delimiter = ':')
    void routingEnabled(int routingEnabled, String expectedRegionsStr) throws Exception {
        final Set<String> expectedRegions = ImmutableSet.copyOf(expectedRegionsStr.split(","));
        final Listener listener = staticResourceListener();
        final ClusterLoadAssignment loadAssignment =
                loadAssignment("cluster",
                               localityLbEndpoints("local", 5),
                               localityLbEndpoints("regionA", 5))
                        .toBuilder().setPolicy(Policy.newBuilder()
                                                     .setOverprovisioningFactor(UInt32Value.of(100)))
                        .build();
        final ZoneAwareLbConfig zoneAwareLbConfig =
                ZoneAwareLbConfig.newBuilder().setRoutingEnabled(percent(routingEnabled)).build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setZoneAwareLbConfig(zoneAwareLbConfig)
                                                             .setHealthyPanicThreshold(percent(0))).build();

        final ClusterLoadAssignment localLoadAssignment =
                loadAssignment("local-cluster",
                               localityLbEndpoints("local", 5),
                               localityLbEndpoints("regionA", 5));
        final Cluster localCluster = createStaticCluster("local-cluster", localLoadAssignment);

        final Bootstrap bootstrap = staticBootstrap(listener, cluster, localCluster)
                .toBuilder()
                .setNode(Node.newBuilder().setLocality(locality("local")))
                .setClusterManager(ClusterManager.newBuilder().setLocalClusterName("local-cluster"))
                .build();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final SettableXdsRandom random = new SettableXdsRandom();
            ctx.setAttr(XdsAttributeKeys.XDS_RANDOM, random);

            final Set<String> selectedRegions = new HashSet<>();
            // run 10 times to guarantee WeightedRampingUpStrategy will choose all endpoints at least once
            for (int i = 0; i < 10; i++) {
                random.fixNextInt(RandomHint.ROUTING_ENABLED, 50);
                final Endpoint selected = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
                assertThat(selected).isNotNull();
                final Locality locality = selected.attr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY)
                                                  .getLocality();
                selectedRegions.add(locality.getRegion());
            }
            assertThat(selectedRegions).containsExactlyInAnyOrderElementsOf(expectedRegions);
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"39:local", "40:local", "41:local,regionA"}, delimiter = ':')
    void panicMode(int threshold, String expectedRegionsStr) throws Exception {
        final Set<String> expectedRegions = ImmutableSet.copyOf(expectedRegionsStr.split(","));
        final Listener listener = staticResourceListener();
        final ClusterLoadAssignment loadAssignment =
                loadAssignment("cluster",
                               localityLbEndpoints("local", 7),
                               localityLbEndpoints("regionA", 5))
                        .toBuilder().setPolicy(Policy.newBuilder()
                                                     .setOverprovisioningFactor(UInt32Value.of(100)))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setHealthyPanicThreshold(percent(threshold)))
                .build();

        // 3 (local healthy) + 5 (regionA healthy) / 20 (total hosts) * 100 = 40 is compared
        // to the panic threshold
        final ClusterLoadAssignment localLoadAssignment =
                loadAssignment("local-cluster",
                               localityLbEndpoints("local", 3, 7),
                               localityLbEndpoints("regionA", 5, 5));
        final Cluster localCluster = createStaticCluster("local-cluster", localLoadAssignment);

        final Bootstrap bootstrap = staticBootstrap(listener, cluster, localCluster)
                .toBuilder()
                .setNode(Node.newBuilder().setLocality(locality("local")))
                .setClusterManager(ClusterManager.newBuilder().setLocalClusterName("local-cluster"))
                .build();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final SettableXdsRandom random = new SettableXdsRandom();
            ctx.setAttr(XdsAttributeKeys.XDS_RANDOM, random);

            final Set<String> selectedRegions = new HashSet<>();
            // run 10 times to guarantee WeightedRampingUpStrategy will choose all endpoints at least once
            for (int i = 0; i < 10; i++) {
                final Endpoint selected = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
                assertThat(selected).isNotNull();
                final Locality locality = selected.attr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY)
                                                  .getLocality();
                selectedRegions.add(locality.getRegion());
            }
            assertThat(selectedRegions).containsExactlyInAnyOrderElementsOf(expectedRegions);
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0:local", "2499:local", "2500:regionA", "2501:regionA", "9999:regionA"
    }, delimiter = ':')
    void pickResidualRegion(long localPercentage, String expectedRegion) throws Exception {
        final Listener listener = staticResourceListener();
        final ClusterLoadAssignment loadAssignment =
                loadAssignment("cluster",
                               localityLbEndpoints("local", 2),
                               localityLbEndpoints("regionA", 8));
        final ZoneAwareLbConfig zoneAwareLbConfig =
                ZoneAwareLbConfig.newBuilder().setMinClusterSize(UInt64Value.of(1)).build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setZoneAwareLbConfig(zoneAwareLbConfig)
                                                             .setHealthyPanicThreshold(percent(0))).build();

        final ClusterLoadAssignment localLoadAssignment =
                loadAssignment("local-cluster",
                               localityLbEndpoints("local", 8),
                               localityLbEndpoints("regionA", 2));
        final Cluster localCluster = createStaticCluster("local-cluster", localLoadAssignment);

        final Bootstrap bootstrap = staticBootstrap(listener, cluster, localCluster)
                .toBuilder()
                .setNode(Node.newBuilder().setLocality(locality("local")))
                .setClusterManager(ClusterManager.newBuilder().setLocalClusterName("local-cluster"))
                .build();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final SettableXdsRandom random = new SettableXdsRandom();
            ctx.setAttr(XdsAttributeKeys.XDS_RANDOM, random);

            // local selection boundary will be:
            // 10000 (factor) * 0.8 (upstream healthy %) / 0.2 (local healthy %) = 2500
            random.fixNextLong(RandomHint.LOCAL_PERCENTAGE, localPercentage);
            final Endpoint selected = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
            assertThat(selected).isNotNull();
            final Locality locality = selected.attr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY)
                                              .getLocality();
            assertThat(locality.getRegion()).isEqualTo(expectedRegion);
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "1000:regionC"
    }, delimiter = ':')
    void multiResidualRegion(long localThreshold, String expectedRegion) throws Exception {
        final Listener listener = staticResourceListener();
        final ClusterLoadAssignment loadAssignment =
                loadAssignment("cluster",
                               localityLbEndpoints("local", 1),
                               localityLbEndpoints("regionA", 2),
                               localityLbEndpoints("regionB", 3),
                               localityLbEndpoints("regionC", 4)
                               );
        final ZoneAwareLbConfig zoneAwareLbConfig =
                ZoneAwareLbConfig.newBuilder().setMinClusterSize(UInt64Value.of(1)).build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setZoneAwareLbConfig(zoneAwareLbConfig)
                                                             .setHealthyPanicThreshold(percent(0))).build();

        final ClusterLoadAssignment localLoadAssignment =
                loadAssignment("local-cluster",
                               localityLbEndpoints("local", 4),
                               localityLbEndpoints("regionA", 3),
                               localityLbEndpoints("regionB", 2),
                               localityLbEndpoints("regionD", 1));
        final Cluster localCluster = createStaticCluster("local-cluster", localLoadAssignment);
        final Bootstrap bootstrap = staticBootstrap(listener, cluster, localCluster)
                .toBuilder()
                .setNode(Node.newBuilder().setLocality(locality("local")))
                .setClusterManager(ClusterManager.newBuilder().setLocalClusterName("local-cluster"))
                .build();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final SettableXdsRandom random = new SettableXdsRandom();
            ctx.setAttr(XdsAttributeKeys.XDS_RANDOM, random);

            // local selection boundary will be:
            // LOCAL_PERCENTAGE
            // local: 10000 (factor) * 0.1 (upstream healthy %) / 0.4 (local healthy %) = 2500 (0 ~ 2499)
            random.fixNextLong(RandomHint.LOCAL_PERCENTAGE, 2500);
            // LOCAL_THRESHOLD
            // regionA: 10000 (factor) * 0.2 (upstream healthy %) - 0.3 (local healthy %) = 0
            // regionB: 10000 (factor) * 0.3 (upstream healthy %) - 0.2 (local healthy %) = 1000 (0 ~ 999)
            // regionC: 10000 (factor) * 0.4 (upstream healthy %) - 0.0 (local healthy %) = 3000 (1000 ~ 2999)
            random.fixNextLong(RandomHint.LOCAL_THRESHOLD, localThreshold);
            final Endpoint selected = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
            assertThat(selected).isNotNull();
            final Locality locality = selected.attr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY)
                                              .getLocality();
            assertThat(locality.getRegion()).isEqualTo(expectedRegion);
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "0:regionA", "1999:regionA", "2000:regionB", "4999:regionB", "5000:regionC", "8999:regionC"
    }, delimiter = ':')
    void onlyUpstreamResidual(long localThreshold, String expectedRegion) throws Exception {
        final Listener listener = staticResourceListener();
        final ClusterLoadAssignment loadAssignment =
                loadAssignment("cluster",
                               localityLbEndpoints("local", 1),
                               localityLbEndpoints("regionA", 2),
                               localityLbEndpoints("regionB", 3),
                               localityLbEndpoints("regionC", 4)
                );
        final ZoneAwareLbConfig zoneAwareLbConfig =
                ZoneAwareLbConfig.newBuilder().setMinClusterSize(UInt64Value.of(1)).build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setZoneAwareLbConfig(zoneAwareLbConfig)
                                                             .setHealthyPanicThreshold(percent(0))).build();

        final ClusterLoadAssignment localLoadAssignment =
                loadAssignment("local-cluster",
                               localityLbEndpoints("local", 4),
                               localityLbEndpoints("local2", 4));
        final Cluster localCluster = createStaticCluster("local-cluster", localLoadAssignment);
        final Bootstrap bootstrap = staticBootstrap(listener, cluster, localCluster)
                .toBuilder()
                .setNode(Node.newBuilder().setLocality(locality("local")))
                .setClusterManager(ClusterManager.newBuilder().setLocalClusterName("local-cluster"))
                .build();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final SettableXdsRandom random = new SettableXdsRandom();
            ctx.setAttr(XdsAttributeKeys.XDS_RANDOM, random);

            // local selection boundary will be:
            // LOCAL_PERCENTAGE
            // local: 10000 (factor) * 0.1 (upstream healthy %) / 0.5 (local healthy %) = 2000 (0 ~ 1999)
            random.fixNextLong(RandomHint.LOCAL_PERCENTAGE, 2000);
            // LOCAL_THRESHOLD
            // regionA: 10000 (factor) * 0.2 (upstream healthy %) - 0.0 (local healthy %) = 2000 (0 ~ 1999)
            // regionB: 10000 (factor) * 0.3 (upstream healthy %) - 0.0 (local healthy %) = 3000 (2000 ~ 4999)
            // regionC: 10000 (factor) * 0.4 (upstream healthy %) - 0.0 (local healthy %) = 4000 (5000 ~ 8999)
            random.fixNextLong(RandomHint.LOCAL_THRESHOLD, localThreshold);
            final Endpoint selected = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
            assertThat(selected).isNotNull();
            final Locality locality = selected.attr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY)
                                              .getLocality();
            assertThat(locality.getRegion()).isEqualTo(expectedRegion);
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"0:39:local"}, delimiter = ':')
    void priorityOneNotUsed(int priority, int selectPriority, String expectedRegion) throws Exception {
        final Listener listener = staticResourceListener();
        final ClusterLoadAssignment loadAssignment =
                loadAssignment("cluster",
                               localityLbEndpoints("local", 3, 2)
                                       .toBuilder().setPriority(priority).build(),
                               localityLbEndpoints("regionA", 2, 3))
                        .toBuilder().setPolicy(Policy.newBuilder()
                                                     .setOverprovisioningFactor(UInt32Value.of(100)))
                        .build();
        final ZoneAwareLbConfig zoneAwareLbConfig =
                ZoneAwareLbConfig.newBuilder().setMinClusterSize(UInt64Value.of(1)).build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setZoneAwareLbConfig(zoneAwareLbConfig)
                                                             .setHealthyPanicThreshold(percent(0))).build();

        final ClusterLoadAssignment localLoadAssignment =
                loadAssignment("local-cluster",
                               localityLbEndpoints("local", 2, 3),
                               localityLbEndpoints("regionA", 3, 0));
        final Cluster localCluster = createStaticCluster("local-cluster", localLoadAssignment);

        final Bootstrap bootstrap = staticBootstrap(listener, cluster, localCluster)
                .toBuilder()
                .setNode(Node.newBuilder().setLocality(locality("local")))
                .setClusterManager(ClusterManager.newBuilder().setLocalClusterName("local-cluster"))
                .build();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final SettableXdsRandom random = new SettableXdsRandom();
            ctx.setAttr(XdsAttributeKeys.XDS_RANDOM, random);

            // boundary of priority set is at 40
            // priority 0: 100 (overprovisioning factor) * 2 (healthy) / 5 (total) = 0 ~ 40
            // priority 1: 40 ~ 100
            random.fixNextInt(RandomHint.SELECT_PRIORITY, selectPriority);
            final Endpoint selected = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
            assertThat(selected).isNotNull();
            final Locality locality = selected.attr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY)
                                              .getLocality();
            assertThat(locality.getRegion()).isEqualTo(expectedRegion);
        }
    }

    private static Stream<Arguments> zoneAwareConfigurationsAreRespected_params() {
        return Stream.of(
                Arguments.of(ZoneAwareLbConfig.newBuilder().setMinClusterSize(UInt64Value.of(10)).build(),
                             "local,regionA"),
                Arguments.of(ZoneAwareLbConfig.newBuilder().setRoutingEnabled(percent(0)).build(),
                             "local,regionA"),
                // the default minClusterSize is 6, so zone-aware isn't performed
                Arguments.of(ZoneAwareLbConfig.newBuilder().setRoutingEnabled(percent(100)).build(),
                             "local,regionA"),
                Arguments.of(ZoneAwareLbConfig.newBuilder()
                                              .setMinClusterSize(UInt64Value.of(5)).build(), "local")
        );
    }

    @ParameterizedTest
    @MethodSource("zoneAwareConfigurationsAreRespected_params")
    void zoneAwareConfigurationsAreRespected(ZoneAwareLbConfig zoneAwareLbConfig,
                                             String expectedRegionsStr) throws Exception {
        final Set<String> expectedRegions = ImmutableSet.copyOf(expectedRegionsStr.split(","));
        final Listener listener = staticResourceListener();
        final ClusterLoadAssignment loadAssignment =
                loadAssignment("cluster",
                               localityLbEndpoints("local", 3),
                               localityLbEndpoints("regionA", 2));
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setZoneAwareLbConfig(zoneAwareLbConfig)
                                                             .setHealthyPanicThreshold(percent(0))).build();
        final ClusterLoadAssignment localLoadAssignment =
                loadAssignment("local-cluster",
                               localityLbEndpoints("local", 2),
                               localityLbEndpoints("regionA", 3));
        final Cluster localCluster = createStaticCluster("local-cluster", localLoadAssignment);

        final Bootstrap bootstrap = staticBootstrap(listener, cluster, localCluster)
                .toBuilder()
                .setNode(Node.newBuilder().setLocality(locality("local")))
                .setClusterManager(ClusterManager.newBuilder().setLocalClusterName("local-cluster"))
                .build();
        final Set<String> selectedRegions = new HashSet<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final SettableXdsRandom random = new SettableXdsRandom();
            ctx.setAttr(XdsAttributeKeys.XDS_RANDOM, random);

            for (int i = 0; i < 10; i++) {
                random.fixNextInt(RandomHint.SELECT_PRIORITY, i * 10);
                final Endpoint selected = loadBalancer.select(ctx, CommonPools.workerGroup()).get();
                assertThat(selected).isNotNull();
                final Locality locality = selected.attr(XdsAttributeKeys.LOCALITY_LB_ENDPOINTS_KEY)
                                                  .getLocality();
                selectedRegions.add(locality.getRegion());
            }
        }
        assertThat(selectedRegions).containsExactlyInAnyOrderElementsOf(expectedRegions);
    }

    static LocalityLbEndpoints localityLbEndpoints(String region, int healthyHosts) {
        return localityLbEndpoints(region, healthyHosts, 0);
    }

    static LocalityLbEndpoints localityLbEndpoints(String region, int healthyHosts, int unhealthyHosts) {
        final ImmutableList.Builder<LbEndpoint> lbEndpointsBuilder = ImmutableList.builder();
        for (int i = 0; i < healthyHosts; i++) {
            lbEndpointsBuilder.add(endpoint(region + "-healthy-" + i + ".com", 8080, HEALTHY));
        }
        for (int i = 0; i < unhealthyHosts; i++) {
            lbEndpointsBuilder.add(endpoint(region + "-unhealthy-" + i + ".com", 8080, UNHEALTHY));
        }
        return XdsTestResources.localityLbEndpoints(locality(region), lbEndpointsBuilder.build());
    }
}
