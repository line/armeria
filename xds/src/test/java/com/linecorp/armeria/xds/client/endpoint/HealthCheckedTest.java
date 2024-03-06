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
import static com.linecorp.armeria.xds.client.endpoint.EndpointTestUtil.staticResourceListener;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;

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
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void basicCase() {
        final Listener listener = staticResourceListener();
        final int weight = 6;

        final List<Integer> healthyPorts = server.server().activePorts().keySet()
                                          .stream().map(InetSocketAddress::getPort)
                                          .collect(Collectors.toList());
        assertThat(healthyPorts).hasSize(3);

        final List<LbEndpoint> healthyEndpoints =
                healthyPorts.stream()
                     .map(port -> endpoint("127.0.0.1", port, weight))
                     .collect(Collectors.toList());
        final LbEndpoint noHealthyEndpoint = endpoint("127.0.0.1", noHealthCheck.httpPort(), weight);
        final List<LbEndpoint> allEndpoints = ImmutableList.<LbEndpoint>builder()
                                                           .addAll(healthyEndpoints)
                                                           .add(noHealthyEndpoint).build();

        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), allEndpoints))
                        .setPolicy(Policy.newBuilder().setWeightedPriorityHealth(true))
                        .build();
        final Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .addHealthChecks(HealthCheck.newBuilder()
                                         .setHttpHealthCheck(HttpHealthCheck.newBuilder()
                                                                            .setPath("/monitor/healthcheck")))
                .build();

        final Bootstrap bootstrap = staticBootstrap(listener, cluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener");
            // disable allowEmptyEndpoints since the first update iteration in no available healthy endpoints
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(listenerRoot, false);
            await().untilAsserted(() -> assertThat(endpointGroup.whenReady()).isDone());

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final Endpoint endpoint = endpointGroup.selectNow(ctx);
            assertThat(endpoint).isNotNull();
            assertThat(endpoint.port()).isIn(healthyPorts);
            assertThat(endpoint.weight()).isEqualTo(weight);
        }
    }
}
