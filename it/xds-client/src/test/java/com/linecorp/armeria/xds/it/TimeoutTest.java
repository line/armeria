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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class TimeoutTest {

    private static final long DEFAULT_RESPONSE_TIMEOUT_MILLIS = Flags.defaultResponseTimeoutMillis();

    //language=YAML
    private static final String connManagerBootstrap =
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
                            domains: [ "*" ]
                            routes:
                              - match:
                                  prefix: /
                                route: %s
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
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
                                port_value: 8080
                """;

    public static Stream<Arguments> responseTimeout_args() {
        return Stream.of(
                // Test route timeout
                Arguments.of("""
                             {
                               cluster: my-cluster,
                               timeout: 123s
                             }
                             """, 123_000),
                // Test route no timeout
                Arguments.of("""
                             {
                               cluster: my-cluster,
                             }
                             """, DEFAULT_RESPONSE_TIMEOUT_MILLIS),
                Arguments.of("""
                             {
                               cluster: my-cluster,
                               timeout: 0s
                             }
                             """, 0)
        );
    }

    @ParameterizedTest
    @MethodSource("responseTimeout_args")
    void responseTimeout(String routeOptions, long expectedMillis) {
        final String yamlBootstrap = connManagerBootstrap.formatted(routeOptions);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(yamlBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res =
                        WebClient.builder(preprocessor)
                                 .responseTimeoutMillis(DEFAULT_RESPONSE_TIMEOUT_MILLIS)
                                 .decorator((delegate, ctx0, req) -> HttpResponse.of(200))
                                 .build()
                                 .blocking()
                                 .execute(HttpRequest.of(HttpMethod.GET, "/"));
                assertThat(res.status().code()).isEqualTo(200);
                ctx = captor.get();
            }
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(expectedMillis);
        }
    }
}
