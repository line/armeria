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

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

class WeightedClusterFilterConfigTest {

    @RegisterExtension
    @Order(0)
    static final ServerExtension echoServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> {
                final String marker = req.headers().get("x-cluster-filter", "none");
                return HttpResponse.of(marker);
            });
            sb.http(0);
        }
    };

    @RegisterExtension
    @Order(1)
    static final XdsControlPlaneExtension controlPlane = new XdsControlPlaneExtension(
            builder -> builder.extensionFactories(configReadingFilterFactory("test.marker.filter"))
    );

    @Test
    void perClusterFilterConfig() {
        // A filter that reads a StringValue config and sets it as a header.
        // cluster-a has typed_per_filter_config that overrides the HCM default,
        // cluster-b has no override and uses the HCM default.
        controlPlane.set(staticCluster("cluster-a"));
        final String version = controlPlane.set(listener("""
                name: test-listener
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
                            prefix: /
                          route:
                            weighted_clusters:
                              clusters:
                              - name: cluster-a
                                weight: 100
                                typed_per_filter_config:
                                  test.marker.filter:
                                    "@type": type.googleapis.com/google.protobuf.StringValue
                                    value: "from-cluster-a"
                    http_filters:
                    - name: test.marker.filter
                      typed_config:
                        "@type": type.googleapis.com/google.protobuf.StringValue
                        value: "default"
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                """));
        controlPlane.awaitListener("test-listener", version);

        try (XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", controlPlane.bootstrap())) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();

            await().untilAsserted(() -> {
                final AggregatedHttpResponse response = client.get("/");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                // cluster-a has typed_per_filter_config override → "from-cluster-a"
                assertThat(response.contentUtf8()).isEqualTo("from-cluster-a");
            });
        }
    }

    @Test
    void perClusterFilterConfigPrecedence() {
        // Route-level typed_per_filter_config sets "from-route".
        // ClusterWeight typed_per_filter_config sets "from-cluster" for the same filter.
        // ClusterWeight should win (higher precedence).
        controlPlane.set(staticCluster("cluster-a"));
        final String version = controlPlane.set(listener("""
                name: test-listener
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
                            prefix: /
                          route:
                            weighted_clusters:
                              clusters:
                              - name: cluster-a
                                weight: 100
                                typed_per_filter_config:
                                  test.marker.filter:
                                    "@type": type.googleapis.com/google.protobuf.StringValue
                                    value: "from-cluster"
                          typed_per_filter_config:
                            test.marker.filter:
                              "@type": type.googleapis.com/google.protobuf.StringValue
                              value: "from-route"
                    http_filters:
                    - name: test.marker.filter
                      typed_config:
                        "@type": type.googleapis.com/google.protobuf.StringValue
                        value: "default"
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                """));
        controlPlane.awaitListener("test-listener", version);

        try (XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", controlPlane.bootstrap())) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();

            await().untilAsserted(() -> {
                final AggregatedHttpResponse response = client.get("/");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                // ClusterWeight typed_per_filter_config wins over route-level
                assertThat(response.contentUtf8()).isEqualTo("from-cluster");
            });
        }
    }

    @Test
    void noOverrideUsesRouteConfig() {
        // cluster-b has NO typed_per_filter_config.
        // Route-level typed_per_filter_config sets "from-route".
        // cluster-b should use the route-level config.
        controlPlane.set(staticCluster("cluster-b"));
        final String version = controlPlane.set(listener("""
                name: test-listener
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
                            prefix: /
                          route:
                            weighted_clusters:
                              clusters:
                              - name: cluster-b
                                weight: 100
                          typed_per_filter_config:
                            test.marker.filter:
                              "@type": type.googleapis.com/google.protobuf.StringValue
                              value: "from-route"
                    http_filters:
                    - name: test.marker.filter
                      typed_config:
                        "@type": type.googleapis.com/google.protobuf.StringValue
                        value: "default"
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                """));
        controlPlane.awaitListener("test-listener", version);

        try (XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", controlPlane.bootstrap())) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();

            await().untilAsserted(() -> {
                final AggregatedHttpResponse response = client.get("/");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                // No cluster-level override → falls back to route-level config
                assertThat(response.contentUtf8()).isEqualTo("from-route");
            });
        }
    }

    @Test
    void snapshotStructureWithPerClusterConfig() {
        // Verify that RouteCluster carries per-cluster chains
        // when typed_per_filter_config is set on ClusterWeight.
        controlPlane.set(staticCluster("cluster-a"), staticCluster("cluster-b"));
        final String version = controlPlane.set(listener("""
                name: test-listener
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
                            prefix: /
                          route:
                            weighted_clusters:
                              clusters:
                              - name: cluster-a
                                weight: 50
                                typed_per_filter_config:
                                  test.marker.filter:
                                    "@type": type.googleapis.com/google.protobuf.StringValue
                                    value: "override-a"
                              - name: cluster-b
                                weight: 50
                    http_filters:
                    - name: test.marker.filter
                      typed_config:
                        "@type": type.googleapis.com/google.protobuf.StringValue
                        value: "default"
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                """));
        controlPlane.awaitListener("test-listener", version);

        final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
        try (ListenerRoot listenerRoot = controlPlane.bootstrap().listenerRoot("test-listener")) {
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> {
                assertThat(snapshotRef.get()).isNotNull();
                final var routeEntries = snapshotRef.get().routeSnapshot()
                                                    .virtualHostSnapshots().get(0).routeEntries();
                assertThat(routeEntries).hasSize(1);
                final var wc = routeEntries.get(0).weightedClusters();
                assertThat(wc).isNotNull().hasSize(2);
                // All RouteCluster instances must have non-null chain accessors
                for (var entry : wc) {
                    assertThat(entry.httpPreClient()).isNotNull();
                    assertThat(entry.rpcPreClient()).isNotNull();
                    assertThat(entry.httpClient()).isNotNull();
                    assertThat(entry.rpcClient()).isNotNull();
                }
                // Single-cluster select also returns chains
                final var selected = routeEntries.get(0).resolve();
                assertThat(selected).isNotNull();
                assertThat(selected.httpPreClient()).isNotNull();
                assertThat(selected.httpClient()).isNotNull();
            });
        }
    }

    private Cluster staticCluster(String name) {
        final String yaml = """
                name: %s
                type: STATIC
                load_assignment:
                  cluster_name: %s
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: %s
                            port_value: %s
                """.formatted(name, name,
                              echoServer.httpSocketAddress().getHostString(),
                              echoServer.httpPort());
        return XdsResourceReader.fromYaml(yaml, Cluster.class);
    }

    private static Listener listener(String yaml) {
        return XdsResourceReader.fromYaml(yaml, Listener.class);
    }

    /**
     * Creates an {@link HttpFilterFactory} that reads the config as a {@link StringValue}
     * and sets the value as the {@code x-cluster-filter} header on the request (preprocessor).
     */
    private static HttpFilterFactory configReadingFilterFactory(String filterName) {
        return new HttpFilterFactory() {
            @Override
            public String name() {
                return filterName;
            }

            @Override
            @Nullable
            public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
                final String marker;
                if (config != null && config.is(StringValue.class)) {
                    try {
                        marker = config.unpack(StringValue.class).getValue();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    marker = "unknown";
                }
                return new XdsHttpFilter() {
                    @Override
                    public com.linecorp.armeria.client.HttpPreprocessor httpPreprocessor() {
                        return (delegate, ctx, req) -> {
                            final HttpRequest newReq = req.withHeaders(
                                    req.headers().toBuilder()
                                       .set("x-cluster-filter", marker)
                                       .build());
                            ctx.updateRequest(newReq);
                            return delegate.execute(ctx, newReq);
                        };
                    }
                };
            }
        };
    }
}
