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

package com.linecorp.armeria.xds.it.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.SignatureException;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.it.XdsCertificateExtension;
import com.linecorp.armeria.xds.it.XdsControlPlaneExtension;
import com.linecorp.armeria.xds.it.XdsResourceReader;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.listener.v3.Listener;

class ServerFilterChainMatchTest {

    private static final String LISTENER_NAME = "filter-chain-listener";

    @RegisterExtension
    @Order(0)
    static final XdsCertificateExtension certA =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(1)
    static final XdsCertificateExtension certDefault =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    // A cert that no server chain ever presents — used only for negative assertions.
    @RegisterExtension
    @Order(2)
    static final XdsCertificateExtension certWrong =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(3)
    static final XdsControlPlaneExtension controlPlane = new XdsControlPlaneExtension();

    @RegisterExtension
    @Order(4)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // Push a minimal listener to unblock XdsServerPlugin.install().
            controlPlane.set(Listener.newBuilder().setName(LISTENER_NAME).build());
            sb.plugin(XdsServerPlugin.of(controlPlane.bootstrap(), LISTENER_NAME));
            sb.service("/hello", (ctx, req) -> HttpResponse.of("hello"));
        }
    };

    @Test
    void matchByTransportProtocol() {
        final Path certPathA = certA.certificateFile().toPath();
        final Path keyPathA = certA.privateKeyFile().toPath();

        //language=YAML
        final String yaml =
                """
                name: %s
                filter_chains:
                  - filter_chain_match:
                      transport_protocol: "tls"
                    filters:
                      - name: envoy.filters.network.http_connection_manager
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters\
                .network.http_connection_manager.v3.HttpConnectionManager
                          stat_prefix: ingress_http
                          route_config:
                            name: local_route
                            virtual_hosts:
                              - name: local_service
                                domains: ["*"]
                                routes:
                                  - match:
                                      prefix: "/"
                                    non_forwarding_action: {}
                          http_filters:
                            - name: envoy.filters.http.router
                    transport_socket:
                      name: envoy.transport_sockets.downstream_tls
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.DownstreamTlsContext
                        common_tls_context:
                          tls_certificates:
                            - certificate_chain:
                                filename: "%s"
                              private_key:
                                filename: "%s"
                  - filter_chain_match:
                      transport_protocol: "raw_buffer"
                    filters:
                      - name: envoy.filters.network.http_connection_manager
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters\
                .network.http_connection_manager.v3.HttpConnectionManager
                          stat_prefix: ingress_http
                          route_config:
                            name: local_route
                            virtual_hosts:
                              - name: local_service
                                domains: ["*"]
                                routes:
                                  - match:
                                      prefix: "/"
                                    non_forwarding_action: {}
                          http_filters:
                            - name: envoy.filters.http.router
                """.formatted(LISTENER_NAME, certPathA, keyPathA);
        final String ver = controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
        controlPlane.awaitListener(LISTENER_NAME, ver);

        // HTTPS connection should match the "tls" chain and present certA.
        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(certA.certificate())
                                                   .build();
        final BlockingWebClient httpsClient =
                WebClient.of(server.httpsUri()).blocking();
        final AggregatedHttpResponse httpsRes = httpsClient.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());
        assertThat(httpsRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(httpsRes.contentUtf8()).isEqualTo("hello");

        // HTTP (plaintext) connection should match the "raw_buffer" chain.
        final AggregatedHttpResponse httpRes =
                WebClient.of(server.httpUri()).blocking().get("/hello");
        assertThat(httpRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(httpRes.contentUtf8()).isEqualTo("hello");
    }

    @Test
    void defaultFilterChainFallback() {
        final Path certPathA = certA.certificateFile().toPath();
        final Path keyPathA = certA.privateKeyFile().toPath();
        final Path certPathDefault = certDefault.certificateFile().toPath();
        final Path keyPathDefault = certDefault.privateKeyFile().toPath();

        // Chain A requires server_names=["no-such-host.example.com"] — will never match.
        // Default chain has certDefault — should always be used.
        //language=YAML
        final String yaml =
                """
                name: %s
                filter_chains:
                  - filter_chain_match:
                      server_names:
                        - "no-such-host.example.com"
                      transport_protocol: "tls"
                    filters:
                      - name: envoy.filters.network.http_connection_manager
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters\
                .network.http_connection_manager.v3.HttpConnectionManager
                          stat_prefix: ingress_http
                          route_config:
                            name: local_route
                            virtual_hosts:
                              - name: local_service
                                domains: ["*"]
                                routes:
                                  - match:
                                      prefix: "/"
                                    non_forwarding_action: {}
                          http_filters:
                            - name: envoy.filters.http.router
                    transport_socket:
                      name: envoy.transport_sockets.downstream_tls
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.DownstreamTlsContext
                        common_tls_context:
                          tls_certificates:
                            - certificate_chain:
                                filename: "%s"
                              private_key:
                                filename: "%s"
                default_filter_chain:
                  filters:
                    - name: envoy.filters.network.http_connection_manager
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters\
                .network.http_connection_manager.v3.HttpConnectionManager
                        stat_prefix: ingress_http
                        route_config:
                          name: local_route
                          virtual_hosts:
                            - name: local_service
                              domains: ["*"]
                              routes:
                                - match:
                                    prefix: "/"
                                  non_forwarding_action: {}
                        http_filters:
                          - name: envoy.filters.http.router
                  transport_socket:
                    name: envoy.transport_sockets.downstream_tls
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.DownstreamTlsContext
                      common_tls_context:
                        tls_certificates:
                          - certificate_chain:
                              filename: "%s"
                            private_key:
                              filename: "%s"
                """.formatted(LISTENER_NAME, certPathA, keyPathA, certPathDefault, keyPathDefault);
        final String ver = controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
        controlPlane.awaitListener(LISTENER_NAME, ver);

        // The specific chain requires server_names=["no-such-host.example.com"]
        // which won't match. The default chain with certDefault should be used.
        final ClientTlsSpec defaultTlsSpec = ClientTlsSpec.builder()
                                                          .trustedCertificates(certDefault.certificate())
                                                          .build();
        final BlockingWebClient defaultClient = WebClient.of(server.httpsUri()).blocking();
        final AggregatedHttpResponse res = defaultClient.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(defaultTlsSpec).build());
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello");

        // certWrong should NOT work since the server presents certDefault.
        final ClientTlsSpec wrongTlsSpec = ClientTlsSpec.builder()
                                                        .trustedCertificates(certWrong.certificate())
                                                        .build();
        final BlockingWebClient wrongClient = WebClient.of(server.httpsUri()).blocking();
        assertThatThrownBy(() -> wrongClient.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(wrongTlsSpec).build()))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasRootCauseInstanceOf(SignatureException.class);
    }

    @Test
    void unmatchedConnectionRejected() {
        final Path certPathA = certA.certificateFile().toPath();
        final Path keyPathA = certA.privateKeyFile().toPath();

        // Only chain matches server_names=["no-such-host.example.com"], no default chain.
        //language=YAML
        final String yaml =
                """
                name: %s
                filter_chains:
                  - filter_chain_match:
                      server_names:
                        - "no-such-host.example.com"
                      transport_protocol: "tls"
                    filters:
                      - name: envoy.filters.network.http_connection_manager
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.filters\
                .network.http_connection_manager.v3.HttpConnectionManager
                          stat_prefix: ingress_http
                          route_config:
                            name: local_route
                            virtual_hosts:
                              - name: local_service
                                domains: ["*"]
                                routes:
                                  - match:
                                      prefix: "/"
                                    non_forwarding_action: {}
                          http_filters:
                            - name: envoy.filters.http.router
                    transport_socket:
                      name: envoy.transport_sockets.downstream_tls
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.DownstreamTlsContext
                        common_tls_context:
                          tls_certificates:
                            - certificate_chain:
                                filename: "%s"
                              private_key:
                                filename: "%s"
                """.formatted(LISTENER_NAME, certPathA, keyPathA);
        final String ver = controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
        controlPlane.awaitListener(LISTENER_NAME, ver);

        // No chain matches (server_names don't match), no default chain.
        // Connection should be rejected.
        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(certWrong.certificate())
                                                   .build();
        final BlockingWebClient client = WebClient.of(server.httpsUri()).blocking();
        assertThatThrownBy(() -> client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build()))
                .isInstanceOf(UnprocessedRequestException.class);
    }
}
