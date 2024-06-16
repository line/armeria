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
import static com.linecorp.armeria.xds.XdsTestResources.localityLbEndpoints;
import static com.linecorp.armeria.xds.XdsTestResources.percent;
import static com.linecorp.armeria.xds.XdsTestResources.staticBootstrap;
import static com.linecorp.armeria.xds.XdsTestResources.staticResourceListener;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.UInt32Value;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.type.v3.Percent;

class PriorityTest {

    @Test
    void basicCase() {
        final Listener listener = staticResourceListener();

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080),
                                 endpoint("127.0.0.1", 8081),
                                 endpoint("127.0.0.1", 8082));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment);

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
        }
    }

    @Test
    void differentWeights() {
        final Listener listener = staticResourceListener();

        final List<LbEndpoint> lbEndpoints =
                ImmutableList.of(endpoint("127.0.0.1", 8080, 1),
                                 endpoint("127.0.0.1", 8081, 1),
                                 endpoint("127.0.0.1", 8082, 2));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment.newBuilder()
                                     .addEndpoints(localityLbEndpoints(
                                             Locality.getDefaultInstance(), lbEndpoints))
                                     .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment);

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);

            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
        }
    }

    @Test
    void differentPriorities() {
        final Listener listener = staticResourceListener();

        final List<LbEndpoint> lbEndpoints0 =
                ImmutableList.of(endpoint("127.0.0.1", 8080, HealthStatus.HEALTHY),
                                 endpoint("127.0.0.1", 8081, HealthStatus.DEGRADED));
        final List<LbEndpoint> lbEndpoints1 =
                ImmutableList.of(endpoint("127.0.0.1", 8082, HealthStatus.HEALTHY),
                                 endpoint("127.0.0.1", 8083, HealthStatus.DEGRADED));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints0, 0))
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints1, 1))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment);

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

            // default overprovisioning factor (140) * 0.5 = 70 will be routed
            // to healthy endpoints for priority 0
            ctx.setAttr(XdsAttributeKeys.SELECTION_HASH, 0);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
            ctx.setAttr(XdsAttributeKeys.SELECTION_HASH, 68);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));

            // 100 - 70 (priority 0) = 30 will be routed to healthy endpoints for priority 1
            ctx.setAttr(XdsAttributeKeys.SELECTION_HASH, 70);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
            ctx.setAttr(XdsAttributeKeys.SELECTION_HASH, 99);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
        }
    }

    @Test
    void degradedEndpoints() {
        final Listener listener = staticResourceListener();

        final List<LbEndpoint> lbEndpoints0 =
                ImmutableList.of(endpoint("127.0.0.1", 8080, HealthStatus.HEALTHY, 1),
                                 endpoint("127.0.0.1", 8081, HealthStatus.UNHEALTHY, 9));
        final List<LbEndpoint> lbEndpoints1 =
                ImmutableList.of(endpoint("127.0.0.1", 8082, HealthStatus.HEALTHY, 1),
                                 endpoint("127.0.0.1", 8083, HealthStatus.DEGRADED, 9));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints0, 0))
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints1, 1))
                        // set overprovisioning factor to 100 for simpler calculation
                        .setPolicy(Policy.newBuilder()
                                         .setOverprovisioningFactor(UInt32Value.of(100))
                                         .setWeightedPriorityHealth(true))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .setCommonLbConfig(CommonLbConfig.newBuilder()
                                                 .setHealthyPanicThreshold(Percent.newBuilder()
                                                                                  .setValue(0)))
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

            // 0 ~ 9 for priority 0 HEALTHY
            ctx.setAttr(XdsAttributeKeys.SELECTION_HASH, 0);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));

            // 10 ~ 19 for priority 1 HEALTHY
            ctx.setAttr(XdsAttributeKeys.SELECTION_HASH, 10);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));

            // 20 ~ 99 for priority 1 DEGRADED
            ctx.setAttr(XdsAttributeKeys.SELECTION_HASH, 20);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8083));
        }
    }

    @Test
    void noHosts() {
        final Listener listener = staticResourceListener();
        final List<LbEndpoint> lbEndpoints0 = ImmutableList.of();
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints0, 0))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .setCommonLbConfig(CommonLbConfig.newBuilder()
                                                 .setHealthyPanicThreshold(Percent.newBuilder()
                                                                                  .setValue(50)))
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot, true);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            await().pollDelay(3, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(endpointGroup.selectNow(ctx)).isNull());
        }
    }

    @Test
    void partialPanic() {
        final Listener listener = staticResourceListener();

        // there are no healthy endpoints in priority0
        final List<LbEndpoint> lbEndpoints0 =
                ImmutableList.of(endpoint("127.0.0.1", 8080, HealthStatus.UNHEALTHY),
                                 endpoint("127.0.0.1", 8081, HealthStatus.UNHEALTHY),
                                 endpoint("127.0.0.1", 8082, HealthStatus.UNHEALTHY));
        final List<LbEndpoint> lbEndpoints1 =
                ImmutableList.of(endpoint("127.0.0.1", 8083, HealthStatus.HEALTHY));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints0, 0))
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints1, 1))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setHealthyPanicThreshold(Percent.newBuilder()
                                                                                              .setValue(50)))
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            ctx.setAttr(XdsAttributeKeys.SELECTION_HASH, 0);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8083));
            ctx.setAttr(XdsAttributeKeys.SELECTION_HASH, 99);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8083));
        }
    }

    @Test
    void totalPanic() {
        final Listener listener = staticResourceListener();

        // 0.33 (healthy) * 140 (overprovisioning factor) < 50 (healthyPanicThreshold)
        final List<LbEndpoint> lbEndpoints0 =
                ImmutableList.of(endpoint("127.0.0.1", 8080, HealthStatus.HEALTHY),
                                 endpoint("127.0.0.1", 8081, HealthStatus.UNHEALTHY),
                                 endpoint("127.0.0.1", 8082, HealthStatus.UNHEALTHY));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints0, 0))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder().setCommonLbConfig(CommonLbConfig.newBuilder()
                                                             .setHealthyPanicThreshold(Percent.newBuilder()
                                                                                              .setValue(50)))
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());

            // When in panic mode, all endpoints are selected regardless of health status
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8080));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8081));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(Endpoint.of("127.0.0.1", 8082));
        }
    }

    @Test
    void onlyUnhealthyPanicDisabled() {
        final Listener listener = staticResourceListener();

        final List<LbEndpoint> lbEndpoints0 =
                ImmutableList.of(endpoint("127.0.0.1", 8080, HealthStatus.UNHEALTHY),
                                 endpoint("127.0.0.1", 8081, HealthStatus.UNHEALTHY),
                                 endpoint("127.0.0.1", 8082, HealthStatus.UNHEALTHY));
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints0))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .setCommonLbConfig(CommonLbConfig.newBuilder().setHealthyPanicThreshold(percent(0)))
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());

            // When in panic mode, all endpoints are selected regardless of health status
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isNull();
            assertThat(endpointGroup.selectNow(ctx)).isNull();
            assertThat(endpointGroup.selectNow(ctx)).isNull();
        }
    }

    private static Stream<Arguments> healthyLoadZeroArgs() {
        return Stream.of(
                // panic mode routes traffic to all endpoints
                Arguments.of(51, Endpoint.of("127.0.0.1", 8080), Endpoint.of("127.0.0.1", 8081)),
                // non-panic mode doesn't route traffic
                Arguments.of(49, null, null)
        );
    }

    @ParameterizedTest
    @MethodSource("healthyLoadZeroArgs")
    void healthyLoadZero(int healthyPanicThreshold, @Nullable Endpoint endpoint1,
                         @Nullable Endpoint endpoint2) {
        final Listener listener = staticResourceListener();
        final List<LbEndpoint> lbEndpoints0 =
                ImmutableList.of(endpoint("127.0.0.1", 8080, HealthStatus.HEALTHY, 1),
                                 endpoint("127.0.0.1", 8081, HealthStatus.UNHEALTHY, 10000));

        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), lbEndpoints0))
                        .setPolicy(Policy.newBuilder()
                                         .setWeightedPriorityHealth(true))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .setCommonLbConfig(CommonLbConfig.newBuilder()
                                                 .setHealthyPanicThreshold(percent(healthyPanicThreshold)))
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());

            // When in panic mode, all endpoints are selected regardless of health status
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint1);
            assertThat(endpointGroup.selectNow(ctx)).isEqualTo(endpoint2);
        }
    }
}
