/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class ExpectedStatusesTest {

    // Server that returns 200 on health check
    @RegisterExtension
    static final ServerExtension healthyServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.service("/health", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    // Server that returns 404 on health check
    @RegisterExtension
    static final ServerExtension notFoundServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.service("/health", (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));
        }
    };

    @Test
    void withExpectedStatuses_nonStandardStatusIsHealthy() {
        // expected_statuses includes both [200,201) and [404,405), so both servers should be healthy
        //language=YAML
        final String yaml = """
                static_resources:
                  listeners:
                  - name: listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
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
                                cluster: cluster1
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                  clusters:
                  - name: cluster1
                    type: STATIC
                    load_assignment:
                      cluster_name: cluster1
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
                    health_checks:
                    - http_health_check:
                        path: /health
                        expected_statuses:
                        - start: 200
                          end: 201
                        - start: 404
                          end: 419
                      timeout: 5s
                      interval: 10s
                      unhealthy_threshold: 1
                      healthy_threshold: 1
                """.formatted(healthyServer.httpPort(), notFoundServer.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(yaml);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster1");
            // Both servers should be healthy
            await().untilAsserted(() -> {
                assertThat(loadBalancer.hostSets().get(0).healthyHostsEndpointGroup().endpoints()
                                       .stream().map(Endpoint::port).collect(Collectors.toSet()))
                        .containsExactlyInAnyOrder(healthyServer.httpPort(), notFoundServer.httpPort());
            });
        }
    }

    @Test
    void withoutExpectedStatuses_nonStandardStatusIsUnhealthy() {
        // No expected_statuses: default behavior (2xx = healthy).
        // The notFoundServer returns 404, which is not 2xx, so it should be unhealthy.
        //language=YAML
        final String yaml = """
                static_resources:
                  listeners:
                  - name: listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
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
                                cluster: cluster1
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                  clusters:
                  - name: cluster1
                    type: STATIC
                    load_assignment:
                      cluster_name: cluster1
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
                    health_checks:
                    - http_health_check:
                        path: /health
                      timeout: 5s
                      interval: 10s
                      unhealthy_threshold: 1
                      healthy_threshold: 1
                """.formatted(healthyServer.httpPort(), notFoundServer.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(yaml);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster1");
            // Only the 200-returning server should be healthy
            await().untilAsserted(() -> {
                assertThat(loadBalancer.hostSets().get(0).healthyHostsEndpointGroup().endpoints()
                                       .stream().map(Endpoint::port).collect(Collectors.toSet()))
                        .containsExactly(healthyServer.httpPort());
            });
        }
    }

    @Test
    void expectedStatusesRange() {
        // expected_statuses with a range [400, 500) — only 4xx are healthy.
        // healthyServer returns 200 (not in range) → unhealthy
        // notFoundServer returns 404 (in range) → healthy
        //language=YAML
        final String yaml = """
                static_resources:
                  listeners:
                  - name: listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
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
                                cluster: cluster1
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                  clusters:
                  - name: cluster1
                    type: STATIC
                    load_assignment:
                      cluster_name: cluster1
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
                    health_checks:
                    - http_health_check:
                        path: /health
                        expected_statuses:
                        - start: 400
                          end: 500
                      timeout: 5s
                      interval: 10s
                      unhealthy_threshold: 1
                      healthy_threshold: 1
                """.formatted(healthyServer.httpPort(), notFoundServer.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(yaml);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot root = xdsBootstrap.listenerRoot("listener")) {
            final XdsLoadBalancer loadBalancer = pollLoadBalancer(root, "cluster1");
            // Only the teapot server (404) should be healthy
            await().untilAsserted(() -> {
                assertThat(loadBalancer.hostSets().get(0).healthyHostsEndpointGroup().endpoints()
                                       .stream().map(Endpoint::port).collect(Collectors.toSet()))
                        .containsExactly(notFoundServer.httpPort());
            });
        }
    }

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
}
