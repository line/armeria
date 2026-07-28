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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.Any;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

class DisabledFilterTest {

    private static final MarkerFilterFactory MARKER_FACTORY = new MarkerFilterFactory();

    private static final Bootstrap SERVER_BOOTSTRAP = XdsResourceReader.fromYaml("""
            static_resources:
              listeners:
                - name: server-listener
                  default_filter_chain:
                    filters:
                      - name: envoy.filters.network.http_connection_manager
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters.network\
            .http_connection_manager.v3.HttpConnectionManager
                          stat_prefix: ingress_http
                          route_config:
                            name: local_route
                            virtual_hosts:
                              - name: local_service
                                domains: ["*"]
                                routes:
                                  - match:
                                      prefix: "/"
                                    non_forwarding_action: {}
                          http_filters:
                            - name: test.marker
                              disabled: true
                            - name: envoy.filters.http.router
            """, Bootstrap.class);

    private static final XdsBootstrap SERVER_XDS_BOOTSTRAP =
            XdsBootstrap.builder(SERVER_BOOTSTRAP)
                        .extensionFactories(MARKER_FACTORY)
                        .build();

    @AfterAll
    static void tearDown() {
        SERVER_XDS_BOOTSTRAP.close();
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.plugin(XdsServerPlugin.of(SERVER_XDS_BOOTSTRAP, "server-listener"));
            sb.http(0);
            sb.service("/", (ctx, req) -> {
                final String downstream = req.headers().get("x-downstream", "none");
                final String upstream = req.headers().get("x-upstream", "none");
                return HttpResponse.of("downstream=" + downstream + ",upstream=" + upstream);
            });
        }
    };

    @Test
    void disabledFiltersAreSkipped() {
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
                          - name: test.marker
                            disabled: true
                          - name: envoy.filters.http.router
                            typed_config:
                              "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                              upstream_http_filters:
                              - name: test.marker
                                disabled: true
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
                """.formatted(server.httpSocketAddress().getHostString(),
                              server.httpPort()), Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap)
                                                     .extensionFactories(MARKER_FACTORY)
                                                     .build();
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            // Downstream and upstream client filters are disabled
            assertThat(response.contentUtf8()).isEqualTo("downstream=none,upstream=none");
            // Server-side filter is also disabled
            assertThat(response.headers().get("x-marker")).isNull();
        }
    }

    private static final class MarkerFilterFactory implements HttpFilterFactory {

        @Override
        public String name() {
            return "test.marker";
        }

        @Override
        public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
            return new XdsHttpFilter() {
                @Override
                public DecoratingHttpClientFunction httpDecorator() {
                    return (delegate, ctx, req) -> {
                        final HttpRequest newReq = req.withHeaders(
                                req.headers().toBuilder()
                                   .set("x-downstream", "applied")
                                   .set("x-upstream", "applied")
                                   .build());
                        ctx.updateRequest(newReq);
                        return delegate.execute(ctx, newReq);
                    };
                }

                @Override
                public DecoratingHttpServiceFunction serviceDecorator() {
                    return (delegate, ctx, req) ->
                            delegate.serve(ctx, req)
                                    .mapHeaders(headers -> headers.toBuilder()
                                                                  .add(HttpHeaderNames.of("x-marker"),
                                                                       "applied")
                                                                  .build());
                }
            };
        }
    }
}
