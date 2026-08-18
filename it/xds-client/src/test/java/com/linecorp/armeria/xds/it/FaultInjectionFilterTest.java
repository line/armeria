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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Stopwatch;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class FaultInjectionFilterTest {

    @RegisterExtension
    static final ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
            sb.http(0);
        }
    };

    private static final Bootstrap SERVER_BOOTSTRAP = XdsResourceReader.fromYaml("""
            static_resources:
              listeners:
                - name: test-listener
                  default_filter_chain:
                    filters:
                      - name: envoy.filters.network.http_connection_manager
                        typed_config:
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
                                    non_forwarding_action: {}
                          http_filters:
                            - {
                                name: envoy.filters.http.fault,
                                typed_config: {
                                  "@type": "type.googleapis.com/envoy.extensions.filters\
            .http.fault.v3.HTTPFault",
                                  abort: {http_status: 503, percentage: {numerator: 100, denominator: HUNDRED}}
                                }
                              }
                            - name: envoy.filters.http.router
            """);

    private static final XdsBootstrap SERVER_XDS_BOOTSTRAP = XdsBootstrap.of(SERVER_BOOTSTRAP);

    @AfterAll
    static void tearDown() {
        SERVER_XDS_BOOTSTRAP.close();
    }

    @RegisterExtension
    static final ServerExtension faultServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.plugin(XdsServerPlugin.of(SERVER_XDS_BOOTSTRAP, "test-listener"));
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    @Test
    void abortAt100Percent() {
        final Bootstrap bootstrap = clientBootstrap(
                "abort: {http_status: 503, percentage: {numerator: 100, denominator: HUNDRED}}");
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse response =
                        WebClient.of(preprocessor).blocking().get("/");
                assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                await().untilAsserted(() -> assertThat(captor.get().log().isComplete()).isTrue());
            }
        }
    }

    @Test
    void abortAt0PercentPassesThrough() {
        final Bootstrap bootstrap = clientBootstrap(
                "abort: {http_status: 503, percentage: {numerator: 0, denominator: HUNDRED}}");
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse response =
                        WebClient.of(preprocessor).blocking().get("/");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.contentUtf8()).isEqualTo("OK");
                await().untilAsserted(() -> assertThat(captor.get().log().isComplete()).isTrue());
            }
        }
    }

    @Test
    void delayAddsLatency() {
        final Bootstrap bootstrap = clientBootstrap(
                "delay: {fixed_delay: 0.500s, percentage: {numerator: 100, denominator: HUNDRED}}");
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final Stopwatch stopwatch = Stopwatch.createStarted();
                final AggregatedHttpResponse response =
                        WebClient.of(preprocessor).blocking().get("/");
                final long elapsedMillis = stopwatch.elapsed().toMillis();
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(elapsedMillis).isGreaterThanOrEqualTo(400);
                await().untilAsserted(() -> assertThat(captor.get().log().isComplete()).isTrue());
            }
        }
    }

    @Test
    void abortAndDelayCombined() {
        final Bootstrap bootstrap = clientBootstrap(
                "abort: {http_status: 429, percentage: {numerator: 100, denominator: HUNDRED}}, " +
                "delay: {fixed_delay: 0.300s, percentage: {numerator: 100, denominator: HUNDRED}}");
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final Stopwatch stopwatch = Stopwatch.createStarted();
                final AggregatedHttpResponse response =
                        WebClient.of(preprocessor).blocking().get("/");
                final long elapsedMillis = stopwatch.elapsed().toMillis();
                assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(elapsedMillis).isGreaterThanOrEqualTo(200);
                await().untilAsserted(() -> assertThat(captor.get().log().isComplete()).isTrue());
            }
        }
    }

    @Test
    void disabledFaultFilterPassesThrough() {
        final Bootstrap bootstrap = clientBootstrap(
                "abort: {http_status: 503, percentage: {numerator: 100, denominator: HUNDRED}}", true);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse response =
                        WebClient.of(preprocessor).blocking().get("/");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.contentUtf8()).isEqualTo("OK");
                await().untilAsserted(() -> assertThat(captor.get().log().isComplete()).isTrue());
            }
        }
    }

    @Test
    void headerMatchAbort() {
        final Bootstrap bootstrap = clientBootstrap(
                "abort: {http_status: 503, percentage: {numerator: 100, denominator: HUNDRED}}, " +
                "headers: [{name: x-fault-inject, present_match: true}]");
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("test-listener", xdsBootstrap)) {
            // Request with matching header gets aborted
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse response =
                        WebClient.of(preprocessor).blocking()
                                 .prepare().get("/").header("x-fault-inject", "true").execute();
                assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                await().untilAsserted(() -> assertThat(captor.get().log().isComplete()).isTrue());
            }
            // Request without matching header passes through
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse response =
                        WebClient.of(preprocessor).blocking().get("/");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.contentUtf8()).isEqualTo("OK");
                await().untilAsserted(() -> assertThat(captor.get().log().isComplete()).isTrue());
            }
        }
    }

    @Test
    void serverSideAbort() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse response =
                    WebClient.of(faultServer.httpUri()).blocking().get("/");
            assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            await().untilAsserted(() -> assertThat(captor.get().log().isComplete()).isTrue());
        }
    }

    private static Bootstrap clientBootstrap(String faultConfig) {
        return clientBootstrap(faultConfig, false);
    }

    private static Bootstrap clientBootstrap(String faultConfig, boolean disabled) {
        return XdsResourceReader.fromYaml("""
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
                          - {
                              name: envoy.filters.http.fault,
                              disabled: %s,
                              typed_config: {
                                "@type": "type.googleapis.com/envoy.extensions.filters.http.fault.v3.HTTPFault",
                                %s
                              }
                            }
                          - name: envoy.filters.http.router
                            typed_config:
                              "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
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
                """.formatted(disabled, faultConfig,
                              backendServer.httpSocketAddress().getHostString(),
                              backendServer.httpPort()));
    }
}
