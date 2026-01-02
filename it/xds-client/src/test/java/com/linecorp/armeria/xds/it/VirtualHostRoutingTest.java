/*
 * Copyright 2025 LY Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class VirtualHostRoutingTest {

    //language=YAML
    private static final String virtualHostRoutingBootstrap =
            """
                static_resources:
                  listeners:
                  - name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service1
                            domains: %s
                            routes:
                              - match:
                                  prefix: "/"
                                route:
                                  cluster: my-cluster1
                          - name: local_service2
                            domains: %s
                            routes:
                              - match:
                                  prefix: "/"
                                route:
                                  cluster: my-cluster2
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: my-cluster1
                    type: STATIC
                    load_assignment:
                      cluster_name: my-cluster1
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8081
                  - name: my-cluster2
                    type: STATIC
                    load_assignment:
                      cluster_name: my-cluster2
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8082
                """;

    static Stream<Arguments> vHostRouting_args() {
        return Stream.of(
                // exact match
                Arguments.of("[ 'foo.com' ]", "[ 'bar.com' ]", "foo.com", 8081),
                Arguments.of("[ 'foo.com' ]", "[ 'bar.com' ]", "bar.com", 8082),
                Arguments.of("[ '*' ]", "[ 'bar.com' ]", "bar.com", 8082),
                Arguments.of("[ 'foo.com', '*' ]", "[ 'bar.com' ]", "bar.com", 8082),
                // exact preferred over prefix/suffix match
                Arguments.of("[ '*ar.com', '*' ]", "[ 'bar.com' ]", "bar.com", 8082),
                Arguments.of("[ 'bar.co*', '*' ]", "[ 'bar.com' ]", "bar.com", 8082),
                // suffix match
                Arguments.of("[ '*' ]", "[ '*foo.com' ]", "afoo.com", 8082),
                Arguments.of("[ '*' ]", "[ '*foo.com' ]", "foo.com", 8081),
                Arguments.of("[ '*' ]", "[ '*foo.com' ]", "oo.com", 8081),
                // longer suffix is matched
                Arguments.of("[ '*o.com' ]", "[ '*oo.com' ]", "foo.com", 8082),
                // prefix match
                Arguments.of("[ '*' ]", "[ 'foo.com*' ]", "foo.coma", 8082),
                Arguments.of("[ '*' ]", "[ 'foo.com*' ]", "foo.com", 8081),
                Arguments.of("[ '*' ]", "[ 'foo.com*' ]", "foo.co", 8081),
                // longer prefix is matched
                Arguments.of("[ 'foo.c*' ]", "[ 'foo.co*' ]", "foo.com", 8082),
                // default vhost
                Arguments.of("[ 'foo.com' ]", "[ '*' ]", "bar.com", 8082),
                Arguments.of("[ 'foo.com' ]", "[ '*' ]", null, 8082),
                // port ignore disabled by default
                Arguments.of("[ '*' ]", "[ 'foo.com:8082' ]", "foo.com:8082", 8082),
                Arguments.of("[ '*' ]", "[ 'foo.com' ]", "foo.com:8082", 8081),
                Arguments.of("[ '*' ]", "[ '[::]:8082' ]", "[::]:8082", 8082),
                Arguments.of("[ '*' ]", "[ '[::]' ]", "[::]:8082", 8081)
        );
    }

    @ParameterizedTest
    @MethodSource("vHostRouting_args")
    void vHostRouting(String vhostPattern1, String vhostPattern2,
                      @Nullable String hostHeader, int expectedPort) {
        final String bootstrap = virtualHostRoutingBootstrap.formatted(vhostPattern1, vhostPattern2);
        final EndpointCollectingDecorator collector = new EndpointCollectingDecorator();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final RequestHeaders requestHeaders;
            if (hostHeader == null) {
                requestHeaders = RequestHeaders.of(HttpMethod.GET, "/");
            } else {
                requestHeaders = RequestHeaders.of(HttpMethod.GET, "/", HttpHeaderNames.AUTHORITY, hostHeader);
            }
            final AggregatedHttpResponse res = WebClient.builder(preprocessor).decorator(collector)
                                                        .build().blocking()
                                                        .execute(HttpRequest.of(requestHeaders));
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(collector.endpointsQueue()).hasSize(1);
            final Endpoint endpoint = collector.endpointsQueue().poll();
            assertThat(endpoint.port()).isEqualTo(expectedPort);
        }
    }

    //language=YAML
    private static final String ignorePortBootstrap =
            """
                static_resources:
                  listeners:
                  - name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: local_route
                          ignore_port_in_host_matching: true
                          virtual_hosts:
                          - name: local_service1
                            domains: %s
                            routes:
                              - match:
                                  prefix: "/"
                                route:
                                  cluster: my-cluster1
                          - name: local_service2
                            domains: %s
                            routes:
                              - match:
                                  prefix: "/"
                                route:
                                  cluster: my-cluster2
                        http_filters:
                        - name: envoy.filters.http.router
                  clusters:
                  - name: my-cluster1
                    type: STATIC
                    load_assignment:
                      cluster_name: my-cluster1
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8081
                  - name: my-cluster2
                    type: STATIC
                    load_assignment:
                      cluster_name: my-cluster2
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8082
                """;

    static Stream<Arguments> ignorePortRouting_args() {
        return Stream.of(
                Arguments.of("[ '*' ]", "[ 'foo.com:8082' ]", "foo.com:8082", 8081),
                Arguments.of("[ '*' ]", "[ 'foo.com' ]", "foo.com:8082", 8082),
                Arguments.of("[ '*' ]", "[ '[::]:8082' ]", "[::]:8082", 8081),
                Arguments.of("[ '*' ]", "[ '[::]' ]", "[::]:8082", 8082)
        );
    }

    @ParameterizedTest
    @MethodSource("ignorePortRouting_args")
    void ignorePortRouting(String vhostPattern1, String vhostPattern2,
                           @Nullable String hostHeader, int expectedPort) {
        final String bootstrap = ignorePortBootstrap.formatted(vhostPattern1, vhostPattern2);
        final EndpointCollectingDecorator collector = new EndpointCollectingDecorator();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final RequestHeaders requestHeaders;
            if (hostHeader == null) {
                requestHeaders = RequestHeaders.of(HttpMethod.GET, "/");
            } else {
                requestHeaders = RequestHeaders.of(HttpMethod.GET, "/", HttpHeaderNames.AUTHORITY, hostHeader);
            }
            final AggregatedHttpResponse res = WebClient.builder(preprocessor).decorator(collector)
                                                        .build().blocking()
                                                        .execute(HttpRequest.of(requestHeaders));
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(collector.endpointsQueue()).hasSize(1);
            final Endpoint endpoint = collector.endpointsQueue().poll();
            assertThat(endpoint.port()).isEqualTo(expectedPort);
        }
    }

    //language=YAML
    private static final String noMatchBootstrap =
            """
            static_resources:
              listeners:
              - name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
            .v3.HttpConnectionManager
                    stat_prefix: http
                    route_config:
                      name: local_route
                      virtual_hosts:
                      - name: local_service1
                        domains: [ "foo.com" ]
                        routes:
                          - match:
                              prefix: "/"
                            route:
                              cluster: my-cluster
                    http_filters:
                    - name: envoy.filters.http.router
              clusters:
              - name: my-cluster
                type: STATIC
                load_assignment:
                  cluster_name: my-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 8081
            """;

    @Test
    void noMatchTest() {
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(noMatchBootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            assertThatThrownBy(() -> WebClient.of(preprocessor).blocking().get("/"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .cause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("No route has been selected for listener");
        }
    }
}
