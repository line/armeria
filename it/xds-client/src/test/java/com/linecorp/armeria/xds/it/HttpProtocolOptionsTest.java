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

import java.util.stream.Stream;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class HttpProtocolOptionsTest {

    @RegisterExtension
    @Order(0)
    static XdsCertificateExtension cert = new XdsCertificateExtension(new SelfSignedCertificateExtension());

    @RegisterExtension
    @Order(1)
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tls(cert.tlsKeyPair());
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    // language=YAML
    private static final String BOOTSTRAP_TEMPLATE =
            """
            static_resources:
              listeners:
              - name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.\
            http_connection_manager.v3.HttpConnectionManager
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
                              cluster: my-cluster
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.\
            http.router.v3.Router
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
                            port_value: %d
                typed_extension_protocol_options:
                  envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
                    "@type": type.googleapis.com/envoy.extensions.upstreams.\
            http.v3.HttpProtocolOptions
                    %s
            """;

    static Stream<Arguments> sessionProtocolSelection() {
        return Stream.of(
                Arguments.of("explicit_http_config: {http2_protocol_options: {}}",
                             SessionProtocol.H2C),
                Arguments.of("explicit_http_config: {http_protocol_options: {}}",
                             SessionProtocol.H1C),
                Arguments.of("auto_config: {}", SessionProtocol.HTTP)
        );
    }

    @ParameterizedTest
    @MethodSource
    void sessionProtocolSelection(String protocolConfig,
                                  SessionProtocol expectedProtocol) {
        final String yaml = BOOTSTRAP_TEMPLATE.formatted(
                server.httpPort(), protocolConfig);
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(yaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse res = client.get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(captor.get().sessionProtocol()).isEqualTo(expectedProtocol);
        }
    }

    // language=YAML
    private static final String TLS_BOOTSTRAP_TEMPLATE =
            """
            static_resources:
              listeners:
              - name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.\
            http_connection_manager.v3.HttpConnectionManager
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
                              cluster: my-cluster
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.\
            http.router.v3.Router
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
                            port_value: %d
                transport_socket:
                  name: envoy.transport_sockets.tls
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.transport_sockets.\
            tls.v3.UpstreamTlsContext
                    common_tls_context: {}
                typed_extension_protocol_options:
                  envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
                    "@type": type.googleapis.com/envoy.extensions.upstreams.\
            http.v3.HttpProtocolOptions
                    %s
            """;

    static Stream<Arguments> tlsSessionProtocolSelection() {
        return Stream.of(
                Arguments.of("explicit_http_config: {http2_protocol_options: {}}",
                             SessionProtocol.H2),
                Arguments.of("explicit_http_config: {http_protocol_options: {}}",
                             SessionProtocol.H1),
                Arguments.of("auto_config: {}", SessionProtocol.HTTPS)
        );
    }

    @ParameterizedTest
    @MethodSource
    void tlsSessionProtocolSelection(String protocolConfig,
                                     SessionProtocol expectedProtocol) {
        final String yaml = TLS_BOOTSTRAP_TEMPLATE.formatted(
                server.httpsPort(), protocolConfig);
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(yaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            final AggregatedHttpResponse res = client.get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(captor.get().sessionProtocol()).isEqualTo(expectedProtocol);
        }
    }
}
