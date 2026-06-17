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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Any;

import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

class HttpFilterCachingTest {

    @Test
    void sameConfigDeduplicatesAcrossRoutes() {
        final AtomicInteger hcmCreateCount = new AtomicInteger();
        final AtomicInteger upstreamCreateCount = new AtomicInteger();
        final HttpFilterFactory hcmFactory = countingFactory("test.hcm.filter", hcmCreateCount);
        final HttpFilterFactory upstreamFactory =
                countingFactory("test.upstream.filter", upstreamCreateCount);

        final Bootstrap bootstrap = XdsResourceReader.fromYaml("""
                static_resources:
                  listeners:
                    - name: test-listener
                      api_listener:
                        api_listener:
                          "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                          stat_prefix: http
                          route_config:
                            name: local_route
                            virtual_hosts:
                            - name: local_service
                              domains: [ "*" ]
                              routes:
                              - match:
                                  prefix: /a
                                route:
                                  cluster: test-cluster
                              - match:
                                  prefix: /b
                                route:
                                  cluster: test-cluster
                              - match:
                                  prefix: /c
                                route:
                                  cluster: test-cluster
                          http_filters:
                          - name: test.hcm.filter
                          - name: envoy.filters.http.router
                            typed_config:
                              "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                              upstream_http_filters:
                              - name: test.upstream.filter
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
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .extensionFactories(hcmFactory, upstreamFactory)
                                                     .build()) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("test-listener");
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> {
                assertThat(snapshotRef.get()).isNotNull();
                final List<RouteEntry> routes =
                        snapshotRef.get().routeSnapshot().virtualHostSnapshots()
                                   .get(0).routeEntries();
                assertThat(routes).hasSize(3);
            });

            // All 3 routes share the same per-filter config (empty map), so each cache
            // creates the filter only once:
            //   preclient cache: 1 create, server cache: 1 create → 2 total
            assertThat(hcmCreateCount.get()).isEqualTo(2);
            //   client (upstream) cache: 1 create
            assertThat(upstreamCreateCount.get()).isEqualTo(1);
        }
    }

    @Test
    void perRouteOverrideBypassesCache() {
        final AtomicInteger hcmCreateCount = new AtomicInteger();
        final AtomicInteger upstreamCreateCount = new AtomicInteger();
        final HttpFilterFactory hcmFactory = countingFactory("test.hcm.filter", hcmCreateCount);
        final HttpFilterFactory upstreamFactory =
                countingFactory("test.upstream.filter", upstreamCreateCount);

        // Route /c has a typed_per_filter_config override for test.hcm.filter,
        // giving it a different cache key from /a and /b.
        final Bootstrap bootstrap = XdsResourceReader.fromYaml("""
                static_resources:
                  listeners:
                    - name: test-listener
                      api_listener:
                        api_listener:
                          "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                          stat_prefix: http
                          route_config:
                            name: local_route
                            virtual_hosts:
                            - name: local_service
                              domains: [ "*" ]
                              routes:
                              - match:
                                  prefix: /a
                                route:
                                  cluster: test-cluster
                              - match:
                                  prefix: /b
                                route:
                                  cluster: test-cluster
                              - match:
                                  prefix: /c
                                route:
                                  cluster: test-cluster
                                typed_per_filter_config:
                                  test.hcm.filter:
                                    "@type": type.googleapis.com/google.protobuf.StringValue
                                    value: "override"
                          http_filters:
                          - name: test.hcm.filter
                          - name: envoy.filters.http.router
                            typed_config:
                              "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                              upstream_http_filters:
                              - name: test.upstream.filter
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
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .extensionFactories(hcmFactory, upstreamFactory)
                                                     .build()) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("test-listener");
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> {
                assertThat(snapshotRef.get()).isNotNull();
                final List<RouteEntry> routes =
                        snapshotRef.get().routeSnapshot().virtualHostSnapshots()
                                   .get(0).routeEntries();
                assertThat(routes).hasSize(3);
            });

            // 2 unique cache keys: {empty} for /a,/b and {test.hcm.filter → override} for /c
            //   preclient cache: 2 creates, server cache: 2 creates → 4 total
            assertThat(hcmCreateCount.get()).isEqualTo(4);
            //   client (upstream) cache: 2 creates (keyed on full config map, not per-filter)
            assertThat(upstreamCreateCount.get()).isEqualTo(2);
        }
    }

    private static HttpFilterFactory countingFactory(String name, AtomicInteger counter) {
        return new HttpFilterFactory() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
                counter.incrementAndGet();
                return XdsHttpFilter.noop();
            }
        };
    }
}
