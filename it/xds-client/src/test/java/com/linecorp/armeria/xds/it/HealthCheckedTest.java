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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

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

    private static XdsLoadBalancer pollLoadBalancer(ListenerRoot root, String clusterName) {
        final AtomicReference<XdsLoadBalancer> lbRef = new AtomicReference<>();
        final SnapshotWatcher<ListenerSnapshot> watcher = (newSnapshot, t) -> {
            final ClusterSnapshot clusterSnapshot =
                    newSnapshot.routeSnapshot().virtualHostSnapshots().get(0).routeEntries().get(0)
                               .clusterSnapshot();
            if (clusterSnapshot != null && clusterName.equals(clusterSnapshot.xdsResource().name())) {
                lbRef.set(clusterSnapshot.loadBalancer());
            }
        };
        root.addSnapshotWatcher(watcher);
        await().untilAsserted(() -> assertThat(lbRef.get()).isNotNull());
        return lbRef.get();
    }

    private static List<Integer> ports(ServerExtension server) {
        return server.server().activePorts().keySet().stream()
                     .map(addr -> addr.getPort()).sorted().collect(Collectors.toList());
    }

    @Test
    void basicCase() {
        final List<Integer> healthyPorts = ports(server);
        final List<Integer> noHcPorts = ports(noHealthCheck);
        final int port1 = healthyPorts.get(0);
        final int port2 = healthyPorts.get(1);
        final int port3 = healthyPorts.get(2);
        final int noHcPort1 = noHcPorts.get(0);
        final int noHcPort2 = noHcPorts.get(1);
        final int noHcPort3 = noHcPorts.get(2);

        //language=YAML
        final String bootstrapYaml =
                """
                static_resources:
                  listeners:
                  - name: listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service
                            domains: ["*"]
                            routes:
                            - match:
                                prefix: /
                              route:
                                cluster: cluster
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                    health_checks:
                    - http_health_check:
                        path: /monitor/healthcheck
                      timeout: 5s
                      interval: 10s
                      unhealthy_threshold: 3
                      healthy_threshold: 2
                """.formatted(port1, port2, port3, noHcPort1, noHcPort2, noHcPort3);

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster");
            assertThat(loadBalancer.hostSets().get(0).healthyHostsEndpointGroup().endpoints()
                                   .stream().map(Endpoint::port).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder(port1, port2, port3);

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final Endpoint endpoint = loadBalancer.selectNow(ctx);
            assertThat(endpoint).isNotNull();
            assertThat(endpoint.port()).isIn(port1, port2, port3);
        }
    }

    @Test
    void allUnhealthy() {
        final List<Integer> noHcPorts = ports(noHealthCheck);
        final int noHcPort1 = noHcPorts.get(0);
        final int noHcPort2 = noHcPorts.get(1);
        final int noHcPort3 = noHcPorts.get(2);

        //language=YAML
        final String bootstrapYaml =
                """
                static_resources:
                  listeners:
                  - name: listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service
                            domains: ["*"]
                            routes:
                            - match:
                                prefix: /
                              route:
                                cluster: cluster
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                          load_balancing_weight: 1
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                          load_balancing_weight: 1
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                          load_balancing_weight: 1
                    health_checks:
                    - http_health_check:
                        path: /monitor/healthcheck
                      timeout: 5s
                      interval: 10s
                      unhealthy_threshold: 1
                      healthy_threshold: 1
                """.formatted(noHcPort1, noHcPort2, noHcPort3);

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster");
            assertThat(loadBalancer.hostSets().get(0).healthyHostsEndpointGroup().endpoints()).isEmpty();

            final ClientRequestContext ctx =
                    ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final Endpoint endpoint = loadBalancer.selectNow(ctx);
            // although all unhealthy, an endpoint is still selected
            assertThat(endpoint).isNotNull();
            assertThat(endpoint.port()).isIn(noHcPort1, noHcPort2, noHcPort3);
        }
    }
}
