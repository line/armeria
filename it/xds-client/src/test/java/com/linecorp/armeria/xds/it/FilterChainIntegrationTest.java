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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.xds.FilterChainSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class FilterChainIntegrationTest {

    @Test
    void filterChainSnapshots() {
        //language=YAML
        final Bootstrap bootstrap = XdsResourceReader.fromYaml("""
                static_resources:
                  listeners:
                    - name: test-listener
                      filter_chains:
                      - filters:
                        - name: envoy.filters.network.http_connection_manager
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                            stat_prefix: chain1
                            route_config:
                              name: route_chain1
                              virtual_hosts:
                              - name: vh1
                                domains: [ "*" ]
                                routes:
                                - match:
                                    prefix: /
                                  route:
                                    cluster: test-cluster
                            http_filters:
                            - name: envoy.filters.http.router
                              typed_config:
                                "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                      - filters:
                        - name: envoy.filters.network.http_connection_manager
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                            stat_prefix: chain2
                            route_config:
                              name: route_chain2
                              virtual_hosts:
                              - name: vh2
                                domains: [ "*" ]
                                routes:
                                - match:
                                    prefix: /
                                  route:
                                    cluster: test-cluster
                            http_filters:
                            - name: envoy.filters.http.router
                              typed_config:
                                "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                      default_filter_chain:
                        filters:
                        - name: envoy.filters.network.http_connection_manager
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                            stat_prefix: default_chain
                            route_config:
                              name: route_default
                              virtual_hosts:
                              - name: vh_default
                                domains: [ "*" ]
                                routes:
                                - match:
                                    prefix: /
                                  route:
                                    cluster: test-cluster
                            http_filters:
                            - name: envoy.filters.http.router
                              typed_config:
                                "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                  clusters:
                    - name: test-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: test-cluster
                        endpoints:
                        - lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: 8080
                """, Bootstrap.class);

        final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("test-listener");
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> {
                assertThat(snapshotRef.get()).isNotNull();
                final ListenerSnapshot snapshot = snapshotRef.get();

                // Verify filter chains
                assertThat(snapshot.filterChains()).hasSize(2);

                final FilterChainSnapshot chain0 = snapshot.filterChains().get(0);
                assertThat(chain0.routeSnapshot()).isNotNull();

                final FilterChainSnapshot chain1 = snapshot.filterChains().get(1);
                assertThat(chain1.routeSnapshot()).isNotNull();

                // Verify default filter chain
                assertThat(snapshot.defaultFilterChain()).isNotNull();
                assertThat(snapshot.defaultFilterChain().routeSnapshot()).isNotNull();
            });
        }
    }
}
