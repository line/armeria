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

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class HostRewriteTest {

    @RegisterExtension
    static final ServerExtension echoServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(
                    req.headers().get("x-selected-cluster", "none") + '|' + req.authority()));
        }
    };

    static Stream<Arguments> protocols() {
        return Stream.of(Arguments.of("http_protocol_options"),
                         Arguments.of("http2_protocol_options"));
    }

    @ParameterizedTest
    @MethodSource("protocols")
    void routeLiteral(String protocol) {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost
                  domains: [original.example]
                  routes:
                  - match: { prefix: / }
                    route:
                      cluster: cluster1
                      host_rewrite_literal: route.example
                """, protocol);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> assertThat(request(client)).isEqualTo("none|route.example"));
        }
    }

    @ParameterizedTest
    @MethodSource("protocols")
    void selectedWeightedClusterLiteral(String protocol) {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost
                  domains: [original.example]
                  routes:
                  - match: { prefix: / }
                    route:
                      weighted_clusters:
                        clusters:
                        - name: cluster1
                          weight: 50
                          host_rewrite_literal: first.example
                          request_headers_to_add:
                          - header: { key: x-selected-cluster, value: first }
                        - name: cluster2
                          weight: 50
                          host_rewrite_literal: second.example
                          request_headers_to_add:
                          - header: { key: x-selected-cluster, value: second }
                """, protocol);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                final Set<String> responses = new HashSet<>();
                // Equal weights alternate with weighted round robin.
                responses.add(request(client));
                responses.add(request(client));
                assertThat(responses).containsExactlyInAnyOrder("first|first.example", "second|second.example");
            });
        }
    }

    static Stream<Arguments> protocolsAndHeaderMutationOrder() {
        return Stream.of(Arguments.of("http_protocol_options", false),
                         Arguments.of("http_protocol_options", true),
                         Arguments.of("http2_protocol_options", false),
                         Arguments.of("http2_protocol_options", true));
    }

    @ParameterizedTest
    @MethodSource("protocolsAndHeaderMutationOrder")
    void routeLiteralOverridesWeightedClusterLiteral(String protocol, boolean mostSpecificWins) {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                most_specific_header_mutations_wins: %s
                virtual_hosts:
                - name: vhost
                  domains: [original.example]
                  routes:
                  - match: { prefix: / }
                    route:
                      weighted_clusters:
                        clusters:
                        - name: cluster1
                          weight: 100
                          host_rewrite_literal: weighted.example
                          request_headers_to_add:
                          - header: { key: x-selected-cluster, value: first }
                      host_rewrite_literal: route.example
                """.formatted(mostSpecificWins), protocol);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> assertThat(request(client)).isEqualTo("first|route.example"));
        }
    }

    @ParameterizedTest
    @MethodSource("protocols")
    void emptyRouteLiteralLeavesWeightedClusterRewrite(String protocol) {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost
                  domains: [original.example]
                  routes:
                  - match: { prefix: / }
                    route:
                      weighted_clusters:
                        clusters:
                        - name: cluster1
                          weight: 100
                          host_rewrite_literal: weighted.example
                      host_rewrite_literal: ""
                """, protocol);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> assertThat(request(client)).isEqualTo("none|weighted.example"));
        }
    }

    private static String request(BlockingWebClient client) {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.GET, "/")
                                                     .authority("original.example")
                                                     .build();
        return client.execute(HttpRequest.of(headers)).contentUtf8();
    }

    private static Bootstrap bootstrap(String routeConfig, String protocol) {
        final String address = echoServer.httpSocketAddress().getHostString();
        final int port = echoServer.httpPort();
        // language=YAML
        final String yaml = """
                static_resources:
                  listeners:
                  - name: listener1
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                %s\
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                  clusters:
                %s\
                %s\
                """.formatted(routeConfig.indent(10), cluster("cluster1", address, port, protocol),
                              cluster("cluster2", address, port, protocol));
        return XdsResourceReader.fromYaml(yaml, Bootstrap.class);
    }

    private static String cluster(String name, String address, int port, String protocol) {
        return """
                - name: %s
                  type: STATIC
                  load_assignment:
                    cluster_name: %s
                    endpoints:
                    - lb_endpoints:
                      - endpoint:
                          address:
                            socket_address:
                              address: %s
                              port_value: %d
                  typed_extension_protocol_options:
                    envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
                      "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
                      explicit_http_config:
                        %s: {}
                """.formatted(name, name, address, port, protocol).indent(2);
    }
}
