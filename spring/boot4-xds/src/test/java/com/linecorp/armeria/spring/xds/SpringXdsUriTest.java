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

package com.linecorp.armeria.spring.xds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsBootstrapRegistry;

@SpringBootTest(classes = SpringXdsUriTest.TestApp.class)
class SpringXdsUriTest {

    private static final String CUSTOM_BOOTSTRAP_NAME = "custom-spring";

    @RegisterExtension
    @Order(0)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
        }
    };

    @DynamicPropertySource
    static void xdsProperties(DynamicPropertyRegistry registry) {
        registry.add("armeria.xds.listener.test-listener", () ->
                //language=YAML
                """
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
                """);
        registry.add("armeria.xds.cluster.test-cluster", () ->
                //language=YAML
                """
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
                """.formatted(server.httpPort()));
    }

    @SpringBootApplication
    static class TestApp {
    }

    @Autowired
    XdsBootstrap xdsBootstrap;

    @AfterAll
    static void tearDown() {
        XdsBootstrapRegistry.deregister(CUSTOM_BOOTSTRAP_NAME);
    }

    @Test
    void defaultBootstrapName() {
        final WebClient client = WebClient.of("xds://spring/test-listener");
        assertThat(client.blocking().get("/hello").contentUtf8()).isEqualTo("world");
    }

    @Test
    void customBootstrapName() {
        XdsBootstrapRegistry.register(CUSTOM_BOOTSTRAP_NAME, xdsBootstrap);
        final WebClient client = WebClient.of("xds://" + CUSTOM_BOOTSTRAP_NAME + "/test-listener");
        assertThat(client.blocking().get("/hello").contentUtf8()).isEqualTo("world");
    }
}
