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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class HeaderMutationTest {

    @RegisterExtension
    static final ServerExtension echoServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> {
                final StringBuilder body = new StringBuilder();
                req.headers().forEach((name, value) -> {
                    if (name.toString().startsWith("x-")) {
                        body.append(name).append('=').append(value).append('\n');
                    }
                });
                return HttpResponse.of(body.toString());
            });
            sb.http(0);
        }
    };

    @Test
    void routeHeaderAddAndRemove() {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost1
                  domains: ["*"]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: cluster1
                    request_headers_to_add:
                    - header:
                        key: x-added
                        value: route-value
                    request_headers_to_remove: ["x-to-remove"]
                """);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                final AggregatedHttpResponse res = client.execute(
                        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                         "x-to-remove", "original")));
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8()).contains("x-added=route-value");
                assertThat(res.contentUtf8()).doesNotContain("x-to-remove");
            });
        }
    }

    @Test
    void multiLevelOrdering_defaultFlagOff() {
        // Default (most_specific_header_mutations_wins=false):
        // Applied order: Route → VHost → RouteConfig (last writer wins → RouteConfig)
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                request_headers_to_add:
                - header:
                    key: x-level
                    value: from-rc
                  append_action: OVERWRITE_IF_EXISTS_OR_ADD
                virtual_hosts:
                - name: vhost1
                  domains: ["*"]
                  request_headers_to_add:
                  - header:
                      key: x-level
                      value: from-vhost
                    append_action: OVERWRITE_IF_EXISTS_OR_ADD
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: cluster1
                    request_headers_to_add:
                    - header:
                        key: x-level
                        value: from-route
                      append_action: OVERWRITE_IF_EXISTS_OR_ADD
                """);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                final AggregatedHttpResponse res = client.get("/");
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8().trim()).isEqualTo("x-level=from-rc");
            });
        }
    }

    @Test
    void multiLevelOrdering_mostSpecificWins() {
        // most_specific_header_mutations_wins=true:
        // Applied order: RouteConfig → VHost → Route (last writer wins → Route)
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                most_specific_header_mutations_wins: true
                request_headers_to_add:
                - header:
                    key: x-level
                    value: from-rc
                  append_action: OVERWRITE_IF_EXISTS_OR_ADD
                virtual_hosts:
                - name: vhost1
                  domains: ["*"]
                  request_headers_to_add:
                  - header:
                      key: x-level
                      value: from-vhost
                    append_action: OVERWRITE_IF_EXISTS_OR_ADD
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: cluster1
                    request_headers_to_add:
                    - header:
                        key: x-level
                        value: from-route
                      append_action: OVERWRITE_IF_EXISTS_OR_ADD
                """);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                final AggregatedHttpResponse res = client.get("/");
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8().trim()).isEqualTo("x-level=from-route");
            });
        }
    }

    @Test
    void weightedClusterHeaderMutation() {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost1
                  domains: ["*"]
                  routes:
                  - match:
                      prefix: /
                    route:
                      weighted_clusters:
                        clusters:
                        - name: cluster1
                          weight: 100
                          request_headers_to_add:
                          - header:
                              key: x-wc
                              value: from-wc
                """);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                final AggregatedHttpResponse res = client.get("/");
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8()).contains("x-wc=from-wc");
            });
        }
    }

    @Test
    void appendIfExistsOrAdd() {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost1
                  domains: ["*"]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: cluster1
                    request_headers_to_add:
                    - header:
                        key: x-test
                        value: appended
                      append_action: APPEND_IF_EXISTS_OR_ADD
                """);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                final AggregatedHttpResponse res = client.execute(
                        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                         "x-test", "original")));
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8()).contains("x-test=original");
                assertThat(res.contentUtf8()).contains("x-test=appended");
            });
        }
    }

    @Test
    void addIfAbsent() {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost1
                  domains: ["*"]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: cluster1
                    request_headers_to_add:
                    - header:
                        key: x-test
                        value: new-value
                      append_action: ADD_IF_ABSENT
                    - header:
                        key: x-new
                        value: added
                      append_action: ADD_IF_ABSENT
                """);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                // x-test already exists → ADD_IF_ABSENT skips
                // x-new does not exist → ADD_IF_ABSENT adds
                final AggregatedHttpResponse res = client.execute(
                        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                         "x-test", "original")));
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8()).contains("x-test=original");
                assertThat(res.contentUtf8()).doesNotContain("x-test=new-value");
                assertThat(res.contentUtf8()).contains("x-new=added");
            });
        }
    }

    @Test
    void overwriteIfExistsOrAdd() {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost1
                  domains: ["*"]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: cluster1
                    request_headers_to_add:
                    - header:
                        key: x-test
                        value: overwritten
                      append_action: OVERWRITE_IF_EXISTS_OR_ADD
                """);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                final AggregatedHttpResponse res = client.execute(
                        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                         "x-test", "original")));
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8()).contains("x-test=overwritten");
                assertThat(res.contentUtf8()).doesNotContain("x-test=original");
            });
        }
    }

    @Test
    void overwriteIfExists() {
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost1
                  domains: ["*"]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: cluster1
                    request_headers_to_add:
                    - header:
                        key: x-existing
                        value: overwritten
                      append_action: OVERWRITE_IF_EXISTS
                    - header:
                        key: x-absent
                        value: should-not-appear
                      append_action: OVERWRITE_IF_EXISTS
                """);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                // x-existing is present → replaced
                // x-absent is not present → NOT added
                final AggregatedHttpResponse res = client.execute(
                        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                         "x-existing", "original")));
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8()).contains("x-existing=overwritten");
                assertThat(res.contentUtf8()).doesNotContain("x-absent");
            });
        }
    }

    @Test
    void removeBeforeAddWithinLevel() {
        // Within a single level, removes happen before adds.
        // Removing x-test then adding x-test=new-value should result in x-test=new-value.
        final Bootstrap bootstrap = bootstrap("""
                name: local_route
                virtual_hosts:
                - name: vhost1
                  domains: ["*"]
                  routes:
                  - match:
                      prefix: /
                    route:
                      cluster: cluster1
                    request_headers_to_remove: ["x-test"]
                    request_headers_to_add:
                    - header:
                        key: x-test
                        value: new-value
                """);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("listener1", xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            await().untilAsserted(() -> {
                final AggregatedHttpResponse res = client.execute(
                        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/",
                                                         "x-test", "original")));
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8()).contains("x-test=new-value");
                assertThat(res.contentUtf8()).doesNotContain("x-test=original");
            });
        }
    }

    private Bootstrap bootstrap(String routeConfig) {
        final String address = echoServer.httpSocketAddress().getHostString();
        final int port = echoServer.httpPort();
        //language=YAML
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
                  - name: cluster1
                    type: STATIC
                    load_assignment:
                      cluster_name: cluster1
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: %s
                                port_value: %d
                """.formatted(routeConfig.indent(10), address, port);
        return XdsResourceReader.fromYaml(yaml, Bootstrap.class);
    }
}
