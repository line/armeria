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

package com.linecorp.armeria.xds.it.athenz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URI;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.athenz.AthenzDocker;
import com.linecorp.armeria.server.athenz.AthenzExtension;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.it.XdsResourceReader;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

@EnabledIfDockerAvailable
class AthenzAccessTokenFilterTest {

    private static final String LISTENER_NAME = "listener1";
    private static final String ATHENZ_RESOURCES = "gen-src/test/resources";

    @RegisterExtension
    @Order(1)
    static final AthenzExtension athenz =
            new AthenzExtension(new File("gen-src/test/resources/docker/docker-compose.yml"));

    @RegisterExtension
    @Order(2)
    static final ServerExtension echoServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/echo-auth", (ctx, req) -> {
                final String auth = req.headers().get("authorization");
                return HttpResponse.of(auth != null ? auth : "no-auth");
            });
            sb.http(0);
        }
    };

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void tokenInjectedIntoAuthorizationHeader() {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml(), Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-auth");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).startsWith("Bearer ");
        }
    }

    @Test
    void existingHeaderOverwrittenByFilter() {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml(), Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.prepare()
                    .get("/echo-auth")
                    .header("authorization", "Bearer existingToken")
                    .execute();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).startsWith("Bearer ");
        }
    }

    @Test
    void tokenInjectedViaUpstreamFilter() {
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(upstreamBootstrapYaml(), Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener(LISTENER_NAME, xdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse response = client.get("/echo-auth");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).startsWith("Bearer ");
        }
    }

    private static String bootstrapYaml() {
        final URI ztsUri = athenz.ztsUri();
        final String serviceCertFile =
                ATHENZ_RESOURCES + AthenzDocker.ATHENZ_CERTS + AthenzDocker.TEST_SERVICE + "/cert.pem";
        final String serviceKeyFile =
                ATHENZ_RESOURCES + AthenzDocker.ATHENZ_CERTS + AthenzDocker.TEST_SERVICE + "/key.pem";
        final String caCertFile = ATHENZ_RESOURCES + AthenzDocker.CA_CERT_FILE;

        //language=YAML
        return """
                static_resources:
                  listeners:
                    - name: %s
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
                                      cluster: echo-cluster
                          http_filters:
                            - name: athenz.access_token_target
                              typed_config:
                                "@type": type.googleapis.com/jp.co.lycorp.ftd.athenz\
                .v1.AccessTokenTargetConfig
                                zts_cluster_name: zts-cluster
                                access_token_target:
                                  target_domain: %s
                                  target_roles: ["%s"]
                                  syntax_version: 1
                            - name: envoy.filters.http.router
                  clusters:
                    - name: echo-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: echo-cluster
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: %s
                                      port_value: %d
                    - name: zts-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: zts-cluster
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: %s
                                      port_value: %d
                      transport_socket:
                        name: envoy.transport_sockets.tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                          common_tls_context:
                            tls_certificates:
                              - certificate_chain:
                                  filename: '%s'
                                private_key:
                                  filename: '%s'
                            validation_context:
                              trusted_ca:
                                filename: '%s'
                """.formatted(
                LISTENER_NAME,
                AthenzDocker.TEST_DOMAIN_NAME, AthenzDocker.USER_ROLE,
                echoServer.httpSocketAddress().getHostString(), echoServer.httpPort(),
                ztsUri.getHost(), ztsUri.getPort(),
                serviceCertFile, serviceKeyFile, caCertFile);
    }

    private static String upstreamBootstrapYaml() {
        final URI ztsUri = athenz.ztsUri();
        final String serviceCertFile =
                ATHENZ_RESOURCES + AthenzDocker.ATHENZ_CERTS + AthenzDocker.TEST_SERVICE + "/cert.pem";
        final String serviceKeyFile =
                ATHENZ_RESOURCES + AthenzDocker.ATHENZ_CERTS + AthenzDocker.TEST_SERVICE + "/key.pem";
        final String caCertFile = ATHENZ_RESOURCES + AthenzDocker.CA_CERT_FILE;

        //language=YAML
        return """
                static_resources:
                  listeners:
                    - name: %s
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
                                      cluster: echo-cluster
                          http_filters:
                            - name: envoy.filters.http.router
                              typed_config:
                                "@type": type.googleapis.com/envoy.extensions.filters.http\
                .router.v3.Router
                                upstream_http_filters:
                                  - name: athenz.access_token_target
                                    typed_config:
                                      "@type": type.googleapis.com/jp.co.lycorp.ftd.athenz\
                .v1.AccessTokenTargetConfig
                                      zts_cluster_name: zts-cluster
                                      access_token_target:
                                        target_domain: %s
                                        target_roles: ["%s"]
                                        syntax_version: 1
                  clusters:
                    - name: echo-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: echo-cluster
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: %s
                                      port_value: %d
                    - name: zts-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: zts-cluster
                        endpoints:
                          - lb_endpoints:
                              - endpoint:
                                  address:
                                    socket_address:
                                      address: %s
                                      port_value: %d
                      transport_socket:
                        name: envoy.transport_sockets.tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                          common_tls_context:
                            tls_certificates:
                              - certificate_chain:
                                  filename: '%s'
                                private_key:
                                  filename: '%s'
                            validation_context:
                              trusted_ca:
                                filename: '%s'
                """.formatted(
                LISTENER_NAME,
                AthenzDocker.TEST_DOMAIN_NAME, AthenzDocker.USER_ROLE,
                echoServer.httpSocketAddress().getHostString(), echoServer.httpPort(),
                ztsUri.getHost(), ztsUri.getPort(),
                serviceCertFile, serviceKeyFile, caCertFile);
    }
}
