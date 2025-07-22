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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class RoutingTest {

    @Test
    void basicCase() {
        final String bootstrap =
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
                                  prefix: "/"
                                route:
                                  cluster: my-cluster1
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
                """;
        final EndpointCollectingDecorator collector = new EndpointCollectingDecorator();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).decorator(collector).build()
                             .blocking().get("/");
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(collector.endpointsQueue()).hasSize(1);
            final Endpoint endpoint = collector.endpointsQueue().poll();
            assertThat(endpoint.port()).isEqualTo(8081);
        }
    }
}
