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
import static com.linecorp.armeria.xds.XdsTestResources.staticBootstrap;
import static com.linecorp.armeria.xds.XdsTestResources.staticResourceListener;
import static com.linecorp.armeria.xds.XdsTestUtil.pollLoadBalancer;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsRandom.RandomHint;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig.ZoneAwareLbConfig;
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint.HealthCheckConfig;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.type.v3.Percent;

class HealthCheckedTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.http(0);
            sb.http(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.service("/monitor/healthcheck", HealthCheckService.builder().build());
        }
    };

    @RegisterExtension
    static ServerExtension noHealthCheck = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.http(0);
            sb.http(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void basicCase() {
        final Listener listener = staticResourceListener();
        final int weight = 6;

        final List<LbEndpoint> healthyEndpoints = server.server().activePorts().keySet()
                                                        .stream().map(addr -> endpoint(addr, weight))
                                                        .collect(Collectors.toList());
        assertThat(healthyEndpoints).hasSize(3);
        final List<LbEndpoint> unhealthyEndpoints = noHealthCheck.server().activePorts().keySet()
                                                                 .stream().map(addr -> endpoint(addr, weight))
                                                                 .collect(Collectors.toList());
        assertThat(unhealthyEndpoints).hasSize(3);
        final List<LbEndpoint> allEndpoints = ImmutableList.<LbEndpoint>builder()
                                                           .addAll(healthyEndpoints)
                                                           .addAll(unhealthyEndpoints).build();

        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), allEndpoints))
                        .setPolicy(Policy.newBuilder().setWeightedPriorityHealth(true))
                        .build();
        final HttpHealthCheck httpHealthCheck = HttpHealthCheck.newBuilder()
                                                               .setPath("/monitor/healthcheck")
                                                               .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .addHealthChecks(HealthCheck.newBuilder()
                                            .setHttpHealthCheck(httpHealthCheck))
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final Endpoint endpoint = loadBalancer.select(ctx, ctx.eventLoop(), Long.MAX_VALUE).join();
            assertThat(endpoint).isNotNull();
            final Set<Integer> healthyPorts = server.server().activePorts().values().stream()
                                                    .map(port -> port.localAddress().getPort())
                                                    .collect(Collectors.toSet());
            assertThat(endpoint.port()).isIn(healthyPorts);
            assertThat(endpoint.weight()).isEqualTo(weight);
        }
    }

    static Stream<Arguments> panicCaseArgs() {
        return Stream.of(
                // Since there are 3 healthy endpoints and 3 unhealthy endpoints,
                // the limit for panic mode is at 50
                Arguments.of(30, false),
                Arguments.of(50, false),
                Arguments.of(51, true),
                Arguments.of(70, true)
        );
    }

    @ParameterizedTest
    @MethodSource("panicCaseArgs")
    void panicCase(double panicThreshold, boolean panicMode) {
        final List<Integer> healthyPorts = ports(server);
        final LbEndpoint healthy1 = endpoint("127.0.0.1", healthyPorts.get(0), HealthStatus.HEALTHY);
        final LbEndpoint healthy2 = endpoint("127.0.0.1", healthyPorts.get(1), HealthStatus.HEALTHY);
        final LbEndpoint degraded1 = endpoint("127.0.0.1", healthyPorts.get(2), HealthStatus.DEGRADED);
        final List<Integer> noHcPorts = ports(noHealthCheck);
        final LbEndpoint healthy3 = endpoint("127.0.0.1", noHcPorts.get(0), HealthStatus.HEALTHY);
        final LbEndpoint healthy4 = endpoint("127.0.0.1", noHcPorts.get(1), HealthStatus.HEALTHY);
        final LbEndpoint degraded2 = endpoint("127.0.0.1", noHcPorts.get(2), HealthStatus.DEGRADED);
        final List<LbEndpoint> allEndpoints =
                ImmutableList.<LbEndpoint>builder().add(healthy1).add(healthy2).add(degraded1)
                             .add(healthy3).add(healthy4).add(degraded2).build();

        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), allEndpoints))
                        .setPolicy(Policy.newBuilder().setWeightedPriorityHealth(true))
                        .build();
        final CommonLbConfig commonLbConfig =
                CommonLbConfig.newBuilder()
                              .setZoneAwareLbConfig(ZoneAwareLbConfig.newBuilder()
                                                                     .setFailTrafficOnPanic(true))
                              .setHealthyPanicThreshold(Percent.newBuilder().setValue(panicThreshold))
                              .build();
        final HealthCheck healthCheck =
                HealthCheck.newBuilder()
                           .setHttpHealthCheck(HttpHealthCheck.newBuilder()
                                                              .setPath("/monitor/healthcheck"))
                           .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .setCommonLbConfig(commonLbConfig)
                .addHealthChecks(healthCheck)
                .build();

        final Listener listener = staticResourceListener();
        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             // disable allowEmptyEndpoints since the first update iteration in no available healthy endpoints
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final SettableXdsRandom random = new SettableXdsRandom();
            ctx.setAttr(ClientXdsAttributeKeys.XDS_RANDOM, random);
            final Set<Endpoint> selectedEndpoints = new HashSet<>();
            for (int i = 0; i < allEndpoints.size(); i++) {
                // try to hit all the ranges of the selection hash
                final int hash = 100 / allEndpoints.size() * i;
                // WeightedRoundRobinStrategy guarantees that each endpoint will be selected at least once
                random.fixNextInt(RandomHint.SELECT_PRIORITY, hash);
                final Endpoint selected = loadBalancer.selectNow(ctx);
                if (selected != null) {
                    selectedEndpoints.add(selected);
                }
            }
            if (panicMode) {
                assertThat(selectedEndpoints).isEmpty();
            } else {
                assertThat(selectedEndpoints).containsExactlyInAnyOrder(
                        Endpoint.of("127.0.0.1", healthyPorts.get(0)),
                        Endpoint.of("127.0.0.1", healthyPorts.get(1)),
                        Endpoint.of("127.0.0.1", healthyPorts.get(2))
                );
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = HealthStatus.class, names = {"UNKNOWN", "UNHEALTHY", "HEALTHY"})
    void disabled(HealthStatus healthStatus) {
        final Listener listener = staticResourceListener();
        final HealthCheckConfig disabledConfig = HealthCheckConfig.newBuilder()
                                                                  .setDisableActiveHealthCheck(true).build();

        final List<LbEndpoint> healthyEndpoints =
                server.server().activePorts().keySet()
                      .stream().map(addr -> testEndpoint(addr, healthStatus, disabledConfig))
                      .collect(Collectors.toList());
        assertThat(healthyEndpoints).hasSize(3);
        final List<LbEndpoint> unhealthyEndpoints =
                noHealthCheck.server().activePorts().keySet()
                             .stream().map(addr -> testEndpoint(addr, healthStatus, disabledConfig))
                             .collect(Collectors.toList());
        assertThat(unhealthyEndpoints).hasSize(3);
        final List<LbEndpoint> allEndpoints = ImmutableList.<LbEndpoint>builder()
                                                           .addAll(healthyEndpoints)
                                                           .addAll(unhealthyEndpoints).build();

        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), allEndpoints))
                        .setPolicy(Policy.newBuilder().setWeightedPriorityHealth(true))
                        .build();
        final HttpHealthCheck httpHealthCheck = HttpHealthCheck.newBuilder()
                                                               .setPath("/monitor/healthcheck")
                                                               .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .addHealthChecks(HealthCheck.newBuilder().setHttpHealthCheck(httpHealthCheck))
                .setCommonLbConfig(CommonLbConfig.newBuilder()
                                                 .setHealthyPanicThreshold(Percent.newBuilder().setValue(0)))
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster", cluster);

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final Endpoint endpoint = loadBalancer.selectNow(ctx);

            // The healthStatus set to the endpoint overrides
            if (healthStatus == HealthStatus.HEALTHY || healthStatus == HealthStatus.UNKNOWN) {
                assertThat(endpoint).isNotNull();
            } else {
                assertThat(healthStatus).isEqualTo(HealthStatus.UNHEALTHY);
                assertThat(endpoint).isNull();
            }
        }
    }

    private static LbEndpoint testEndpoint(InetSocketAddress address, HealthStatus healthStatus,
                                           HealthCheckConfig config) {
        return endpoint(address.getAddress().getHostAddress(), address.getPort(),
                        Metadata.getDefaultInstance(), 1, healthStatus, config);
    }

    private static List<Integer> ports(ServerExtension server) {
        return server.server().activePorts().keySet().stream()
                     .map(InetSocketAddress::getPort).collect(Collectors.toList());
    }
}
