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

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class RouteMatcherTest {
    //language=YAML
    private static final String combinedBootstrap =
            """
                static_resources:
                  listeners:
                  - name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                              - match:
                                  path: /api/users
                                  headers:
                                    - name: "user-type"
                                      string_match:
                                        exact: "admin"
                                  query_parameters:
                                    - name: "version"
                                      string_match:
                                        exact: "v1"
                                route:
                                  cluster: my-cluster1
                              - match:
                                  prefix: /
                                route:
                                  cluster: my-cluster2
                        http_filters:
                        - name: envoy.filters.http.router
                  clusters:
                  - name: my-cluster1
                    type: STATIC
                    load_assignment:
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
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8082
                """;

    static Stream<Arguments> combinedMatch_args() {
        return Stream.of(
                // All conditions match - should route to my-cluster1
                Arguments.of("/api/users?version=v1", "user-type", "admin", 8081),
                // Path doesn't match - should route to my-cluster2
                Arguments.of("/api/posts?version=v1", "user-type", "admin", 8082),
                // Header doesn't match - should route to my-cluster2
                Arguments.of("/api/users?version=v1", "user-type", "user", 8082),
                // Query parameter doesn't match - should route to my-cluster2
                Arguments.of("/api/users?version=v2", "user-type", "admin", 8082)
        );
    }

    @ParameterizedTest
    @MethodSource("combinedMatch_args")
    void combinedMatch(String path, String headerName, String headerValue, int expectedPort) {
        final EndpointCollectingDecorator collector = new EndpointCollectingDecorator();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(combinedBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {

            final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, path, headerName, headerValue);
            final AggregatedHttpResponse res = WebClient.builder(preprocessor).decorator(collector)
                                                        .build().blocking()
                                                        .execute(HttpRequest.of(headers));
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(collector.endpointsQueue()).hasSize(1);
            final Endpoint endpoint = collector.endpointsQueue().poll();
            assertThat(endpoint.port()).isEqualTo(expectedPort);
        }
    }

    //language=YAML
    private static final String matchBootstrap =
            """
                static_resources:
                  listeners:
                  - name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                              - match:
                                  prefix: /
                                  headers:
                                    %s
                                route:
                                  cluster: my-cluster1
                              - match:
                                  prefix: /
                                  headers:
                                    %s
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
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8082
                """;

    static Stream<Arguments> headerMatch_args() {
        return Stream.of(
                // exact match
                Arguments.of("""
                             [{
                               "name": ":method",
                               "string_match": {
                                 "exact": "POST"
                               }
                             }]
                             """,
                             """
                              [{
                                "name": ":method",
                                "string_match": {
                                  "exact": "GET"
                                }
                              }]
                             """, RequestHeaders.of(HttpMethod.POST, "/"), 8081),
                Arguments.of("""
                             [{
                               "name": ":method",
                               "string_match": {
                                 "exact": "POST"
                               }
                             }]
                             """,
                             """
                              [{
                                "name": ":method",
                                "string_match": {
                                  "exact": "GET"
                                }
                              }]
                             """, RequestHeaders.of(HttpMethod.GET, "/"), 8082),
                // regex
                Arguments.of("""
                             [{
                               "name": ":method",
                               "string_match": {
                                 "prefix": "POST"
                               }
                             }]
                             """,
                             """
                              [{
                                "name": ":method",
                                "string_match": {
                                  "exact": "GET"
                                }
                              }]
                             """, RequestHeaders.of(HttpMethod.GET, "/"), 8082)
        );
    }

    @ParameterizedTest
    @MethodSource("headerMatch_args")
    void headerMatch(String match1, String match2, RequestHeaders requestHeaders, int expectedPort) {
        final String bootstrap = matchBootstrap.formatted(match1, match2);
        final EndpointCollectingDecorator collector = new EndpointCollectingDecorator();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
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
    private static final String queryParamBootstrap =
            """
                static_resources:
                  listeners:
                  - name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                              - match:
                                  prefix: /
                                  query_parameters:
                                    %s
                                route:
                                  cluster: my-cluster1
                              - match:
                                  prefix: /
                                  query_parameters:
                                    %s
                                route:
                                  cluster: my-cluster2
                        http_filters:
                        - name: envoy.filters.http.router
                  clusters:
                  - name: my-cluster1
                    type: STATIC
                    load_assignment:
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
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8082
                """;

    static Stream<Arguments> queryParamMatch_args() {
        return Stream.of(
                // exact string match
                Arguments.of("""
                             [{
                               "name": "param1",
                               "string_match": {
                                 "exact": "value1"
                               }
                             }]
                             """,
                             """
                              [{
                                "name": "param2",
                                "string_match": {
                                  "exact": "value2"
                                }
                              }]
                             """, "/path?param1=value1", 8081),
                Arguments.of("""
                             [{
                               "name": "param1",
                               "string_match": {
                                 "exact": "value1"
                               }
                             }]
                             """,
                             """
                              [{
                                "name": "param2",
                                "string_match": {
                                  "exact": "value2"
                                }
                              }]
                             """, "/path?param2=value2", 8082),
                // present match
                Arguments.of("""
                             [{
                               "name": "param1",
                               "present_match": true
                             }]
                             """,
                             """
                              [{
                                "name": "param2",
                                "present_match": true
                              }]
                             """, "/path?param1=any_value", 8081),
                Arguments.of("""
                             [{
                               "name": "param1",
                               "present_match": true
                             }]
                             """,
                             """
                              [{
                                "name": "param2",
                                "present_match": true
                              }]
                             """, "/path?param2=any_value", 8082)
        );
    }

    @ParameterizedTest
    @MethodSource("queryParamMatch_args")
    void queryParamMatch(String match1, String match2, String path, int expectedPort) {
        final String bootstrap = queryParamBootstrap.formatted(match1, match2);
        final EndpointCollectingDecorator collector = new EndpointCollectingDecorator();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res = WebClient.builder(preprocessor).decorator(collector)
                                                        .build().blocking()
                                                        .execute(HttpRequest.of(HttpMethod.GET, path));
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(collector.endpointsQueue()).hasSize(1);
            final Endpoint endpoint = collector.endpointsQueue().poll();
            assertThat(endpoint.port()).isEqualTo(expectedPort);
        }
    }

    //language=YAML
    private static final String grpcBootstrap =
            """
                static_resources:
                  listeners:
                  - name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                              - match:
                                  prefix: /
                                  grpc: {}
                                route:
                                  cluster: my-cluster1
                              - match:
                                  prefix: /
                                route:
                                  cluster: my-cluster2
                        http_filters:
                        - name: envoy.filters.http.router
                  clusters:
                  - name: my-cluster1
                    type: STATIC
                    load_assignment:
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
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8082
                """;

    static Stream<Arguments> grpcRequestMatch_args() {
        return Stream.of(
                // gRPC request (should route to my-cluster1)
                Arguments.of(true, 8081),
                // non-gRPC request (should route to my-cluster2)
                Arguments.of(false, 8082)
        );
    }

    @ParameterizedTest
    @MethodSource("grpcRequestMatch_args")
    void grpcRequestMatch(boolean isGrpc, int expectedPort) {
        final EndpointCollectingDecorator collector = new EndpointCollectingDecorator();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(grpcBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {

            // Build request with or without gRPC content type
            final RequestHeaders headers;
            if (isGrpc) {
                headers = RequestHeaders.of(HttpMethod.POST, "/path",
                                            HttpHeaderNames.CONTENT_TYPE, "application/grpc");
            } else {
                headers = RequestHeaders.of(HttpMethod.POST, "/path");
            }

            final AggregatedHttpResponse res = WebClient.builder(preprocessor).decorator(collector)
                                                        .build().blocking()
                                                        .execute(HttpRequest.of(headers));
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(collector.endpointsQueue()).hasSize(1);
            final Endpoint endpoint = collector.endpointsQueue().poll();
            assertThat(endpoint.port()).isEqualTo(expectedPort);
        }
    }

    //language=YAML
    private static final String pathBootstrap =
            """
                static_resources:
                  listeners:
                  - name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                              - match:
                                  %s
                                route:
                                  cluster: my-cluster1
                              - match:
                                  %s
                                route:
                                  cluster: my-cluster2
                        http_filters:
                        - name: envoy.filters.http.router
                  clusters:
                  - name: my-cluster1
                    type: STATIC
                    load_assignment:
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
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8082
                """;

    static Stream<Arguments> pathMatch_args() {
        return Stream.of(
                // prefix match
                Arguments.of(
                        "prefix: '/api'",
                        "prefix: '/users'",
                        "/api/resource",
                        8081
                ),
                Arguments.of(
                        "prefix: '/api'",
                        "prefix: '/users'",
                        "/users/123",
                        8082
                ),
                // exact path match
                Arguments.of(
                        "path: '/exact-path'",
                        "prefix: '/'",
                        "/exact-path",
                        8081
                ),
                Arguments.of(
                        "path: '/exact-path'",
                        "prefix: '/'",
                        "/not-exact-path",
                        8082
                )
        );
    }

    @ParameterizedTest
    @MethodSource("pathMatch_args")
    void pathMatch(String pathMatch1, String pathMatch2, String path, int expectedPort) {
        final String bootstrap = pathBootstrap.formatted(pathMatch1, pathMatch2);
        final EndpointCollectingDecorator collector = new EndpointCollectingDecorator();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res = WebClient.builder(preprocessor).decorator(collector)
                                                        .build().blocking()
                                                        .execute(HttpRequest.of(HttpMethod.GET, path));
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(collector.endpointsQueue()).hasSize(1);
            final Endpoint endpoint = collector.endpointsQueue().poll();
            assertThat(endpoint.port()).isEqualTo(expectedPort);
        }
    }
}
