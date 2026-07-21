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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

class HttpFilterOrderingTest {

    @RegisterExtension
    static final ServerExtension echoServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/echo-order", (ctx, req) -> {
                final String order = req.headers().get("x-order", "none");
                return HttpResponse.of(order);
            });
            sb.http(0);
        }
    };

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void serverFilterOrdering() {
        final List<String> executionOrder = new CopyOnWriteArrayList<>();
        final HttpFilterFactory filterA = orderingFilterFactory("test.filter.a", "A", executionOrder);
        final HttpFilterFactory filterB = orderingFilterFactory("test.filter.b", "B", executionOrder);

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
                                  prefix: /
                                route:
                                  cluster: test-cluster
                          http_filters:
                          - name: test.filter.a
                          - name: test.filter.b
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
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .extensionFactories(filterA, filterB)
                                                     .build()) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("test-listener");
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> {
                assertThat(snapshotRef.get()).isNotNull();
                final ListenerSnapshot snapshot = snapshotRef.get();
                assertThat(snapshot.routeSnapshot()).isNotNull();
                final List<RouteEntry> routes =
                        snapshot.routeSnapshot().virtualHostSnapshots().get(0).routeEntries();
                assertThat(routes).hasSize(1);
                assertThat(routes.get(0).httpService()).isNotNull();
            });

            final HttpService service = snapshotRef.get().routeSnapshot()
                                                    .virtualHostSnapshots().get(0)
                                                    .routeEntries().get(0).httpService();
            final ServiceRequestContext ctx =
                    ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            try {
                @SuppressWarnings("unused")
                final HttpResponse unused = service.serve(ctx, ctx.request());
            } catch (Exception e) {
                // DelegatingHttpService may throw if no delegate is set; ordering is still recorded
            }

            // HCM order [a, b, router] → first filter is outermost → executes first
            assertThat(executionOrder).containsExactly("A", "B");
        }
    }

    @Test
    void preprocessorOrdering() {
        final HttpFilterFactory filterA = headerAppendingFilterFactory(
                "test.filter.a", "A");
        final HttpFilterFactory filterB = headerAppendingFilterFactory(
                "test.filter.b", "B");

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml(), Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .eventExecutor(eventLoop.get())
                                                     .extensionFactories(filterA, filterB)
                                                     .build();
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-order");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            // HCM order [a, b, router] → a is outermost → appends first
            assertThat(response.contentUtf8()).isEqualTo("A,B");
        }
    }

    @Test
    void clientDecoratorOrdering() {
        final HttpFilterFactory filterA = headerAppendingFilterFactory(
                "test.filter.a", "A");
        final HttpFilterFactory filterB = headerAppendingFilterFactory(
                "test.filter.b", "B");

        // upstream_http_filters are specified inside the Router config
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(upstreamBootstrapYaml(),
                                                               Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .eventExecutor(eventLoop.get())
                                                     .extensionFactories(filterA, filterB)
                                                     .build();
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-order");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            // upstream_http_filters order [a, b] → a is outermost → appends first
            assertThat(response.contentUtf8()).isEqualTo("A,B");
        }
    }

    private String bootstrapYaml() {
        return """
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
                                  prefix: /
                                route:
                                  cluster: test-cluster
                          http_filters:
                          - name: test.filter.a
                          - name: test.filter.b
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
                                  address: %s
                                  port_value: %s
                """.formatted(echoServer.httpSocketAddress().getHostString(),
                              echoServer.httpPort());
    }

    private String upstreamBootstrapYaml() {
        return """
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
                                  prefix: /
                                route:
                                  cluster: test-cluster
                          http_filters:
                          - name: envoy.filters.http.router
                            typed_config:
                              "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                              upstream_http_filters:
                              - name: test.filter.a
                              - name: test.filter.b
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
                                  address: %s
                                  port_value: %s
                """.formatted(echoServer.httpSocketAddress().getHostString(),
                              echoServer.httpPort());
    }

    private static HttpFilterFactory orderingFilterFactory(String name, String marker,
                                                           List<String> executionOrder) {
        return new HttpFilterFactory() {
            @Override
            public String name() {
                return name;
            }

            @Override
            @Nullable
            public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
                return new XdsHttpFilter() {
                    @Override
                    public DecoratingHttpServiceFunction serviceDecorator() {
                        return (delegate, ctx, req) -> {
                            executionOrder.add(marker);
                            return delegate.serve(ctx, req);
                        };
                    }
                };
            }
        };
    }

    private static HttpFilterFactory headerAppendingFilterFactory(String name, String marker) {
        return new HttpFilterFactory() {
            @Override
            public String name() {
                return name;
            }

            @Override
            @Nullable
            public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
                return new XdsHttpFilter() {
                    @Override
                    public DecoratingHttpClientFunction httpDecorator() {
                        return (delegate, ctx, req) -> {
                            final String existing = req.headers().get("x-order", "");
                            final String newVal = existing.isEmpty() ? marker
                                                                     : existing + ',' + marker;
                            final HttpRequest newReq = req.withHeaders(
                                    req.headers().toBuilder().set("x-order", newVal).build());
                            ctx.updateRequest(newReq);
                            return delegate.execute(ctx, newReq);
                        };
                    }
                };
            }
        };
    }
}
