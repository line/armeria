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
import static org.awaitility.Awaitility.await;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class PreprocessorErrorTest {

    static Stream<Arguments> testCases() {
        return Stream.of(
                Arguments.of(
                        """
                        dynamic_resources:
                          lds_config:
                            api_config_source:
                              api_type: GRPC
                              grpc_services:
                                - envoy_grpc:
                                    cluster_name: bootstrap-cluster
                        static_resources:
                          clusters:
                          - name: my-cluster1
                            type: STATIC
                            load_assignment:
                              cluster_name: my-cluster1
                              endpoints:
                              - lb_endpoints:
                        """,
                        TimeoutException.class,
                        "Couldn't select a snapshot for listener 'my-listener'."
                ),
                Arguments.of(
                        """
                        static_resources:
                          listeners:
                          - name: my-listener
                            api_listener:
                              api_listener:
                                "@type": type.googleapis.com/envoy.extensions.filters.network.\
                        http_connection_manager.v3.HttpConnectionManager
                                stat_prefix: conn_manager
                                route_config:
                                  name: my-route
                                  virtual_hosts:
                                  - name: local_service1
                                    domains: [ "*" ]
                                    routes:
                                      - match:
                                          path: /no-match
                                        route:
                                          cluster: my-cluster
                                http_filters:
                                - name: envoy.filters.http.router
                          clusters:
                          - name: my-cluster
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
                        """,
                        IllegalArgumentException.class,
                        "No route has been selected for listener "
                ),
                Arguments.of(
                        """
                        static_resources:
                          listeners:
                          - name: my-listener
                            api_listener:
                              api_listener:
                                "@type": type.googleapis.com/envoy.extensions.filters.network.\
                        http_connection_manager.v3.HttpConnectionManager
                                stat_prefix: conn_manager
                                route_config:
                                  name: my-route
                                  virtual_hosts:
                                  - name: local_service1
                                    domains: [ "*" ]
                                    routes:
                                      - match:
                                          prefix: /
                                        route:
                                          cluster: my-cluster
                                http_filters:
                                - name: envoy.filters.http.router
                          clusters:
                          - name: my-cluster
                            type: STATIC
                            load_assignment:
                              cluster_name: my-cluster1
                              endpoints:
                              - lb_endpoints:
                        """,
                        TimeoutException.class,
                        "Failed to select an endpoint"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("testCases")
    void testPreprocessorErrors(String bootstrapYaml,
                                Class<? extends Throwable> expectedType,
                                String expectedMessage) {
        try (XdsBootstrap bootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrapYaml));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", bootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor();
             ClientFactory cf = ClientFactory.builder()
                                             .connectTimeoutMillis(1000)
                                             .build()) {
            final BlockingWebClient client = WebClient.builder(preprocessor)
                                                      .factory(cf)
                                                      .responseTimeoutMillis(1000)
                                                      .build()
                                                      .blocking();

            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .cause()
                    .isInstanceOf(expectedType)
                    .hasMessageStartingWith(expectedMessage);

            final DefaultClientRequestContext ctx = (DefaultClientRequestContext) captor.get();
            await().untilAsserted(() -> assertThat(ctx.whenInitialized()).isDone());
            await().untilAsserted(() -> assertThat(ctx.log().isComplete()).isTrue());
        }
    }
}
