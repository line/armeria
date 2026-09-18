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

package com.linecorp.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.xds.SpringXdsAutoConfiguration;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class SpringXdsUriTest {

    @RegisterExtension
    @Order(0)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(SpringXdsAutoConfiguration.class))
                    .withPropertyValues(
                            "armeria.xds.listener.test-listener=" + listenerYaml(),
                            "armeria.xds.cluster.test-cluster=" + clusterYaml());

    @Test
    void defaultBootstrapName() {
        contextRunner.run(context -> {
            try {
                final WebClient client = WebClient.of("xds://spring/test-listener");
                assertThat(client.blocking().get("/hello").contentUtf8()).isEqualTo("world");
            } finally {
                XdsBootstrapRegistry.deregister("spring");
            }
        });
    }

    @Test
    void customBootstrapName() {
        contextRunner.withPropertyValues("armeria.xds.bootstrap-name=custom-spring")
                     .run(context -> {
                         try {
                             final WebClient client =
                                     WebClient.of("xds://custom-spring/test-listener");
                             assertThat(client.blocking().get("/hello").contentUtf8())
                                     .isEqualTo("world");
                         } finally {
                             XdsBootstrapRegistry.deregister("custom-spring");
                         }
                     });
    }

    private static String listenerYaml() {
        return """
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
                          domains: ["*"]
                          routes:
                            - match:
                                prefix: /
                              route:
                                cluster: test-cluster
                    http_filters:
                      - name: envoy.filters.http.router
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """;
    }

    private String clusterYaml() {
        return """
                name: test-cluster
                type: STATIC
                load_assignment:
                  cluster_name: test-cluster
                  endpoints:
                    - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %d
                """.formatted(server.httpPort());
    }
}
