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

import java.nio.file.Path;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;
import com.linecorp.armeria.xds.it.XdsCertificateExtension;
import com.linecorp.armeria.xds.it.XdsControlPlaneExtension;
import com.linecorp.armeria.xds.it.XdsResourceReader;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class ServerXdsTest {

    private static final String LISTENER_NAME = "server-listener";
    private static final ServerPort xdsPort =
            new ServerPort(0, SessionProtocol.HTTP, SessionProtocol.HTTPS);

    @RegisterExtension
    @Order(0)
    static final XdsCertificateExtension xdsCert =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(1)
    static final XdsControlPlaneExtension controlPlane = new XdsControlPlaneExtension();

    @RegisterExtension
    @Order(2)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final Path certPath = xdsCert.certificateFile().toPath();
            final Path keyPath = xdsCert.privateKeyFile().toPath();

            //language=YAML
            final String yaml =
                    """
                    name: %s
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
                    """.formatted(LISTENER_NAME, certPath, keyPath);
            controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
            sb.plugin(XdsServerPlugin.builder(controlPlane.bootstrap(), LISTENER_NAME)
                                     .port(xdsPort)
                                     .build());
            sb.service("/hello", (ctx, req) -> HttpResponse.of("hello from xds"));
        }
    };

    @Test
    void mutualTlsTest() {
        final Path certPath = xdsCert.certificateFile().toPath();

        //language=YAML
        final String clientBootstrapYaml =
                """
                static_resources:
                  listeners:
                    - name: client-listener
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
                                      prefix: "/"
                                    route:
                                      cluster: server-cluster
                          http_filters:
                            - name: envoy.filters.http.router
                              typed_config:
                                "@type": type.googleapis.com/envoy.extensions\
                .filters.http.router.v3.Router
                  clusters:
                    - name: server-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: server-cluster
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
                          "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                          common_tls_context:
                            validation_context:
                              trusted_ca:
                                filename: "%s"
                """.formatted(xdsPort.actualPort(), certPath);

        final Bootstrap clientBootstrap =
                XdsResourceReader.fromYaml(clientBootstrapYaml, Bootstrap.class);
        try (XdsBootstrap clientXdsBootstrap = XdsBootstrap.of(clientBootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("client-listener",
                                                                               clientXdsBootstrap)) {
            final BlockingWebClient client = WebClient.of(preprocessor).blocking();
            assertThat(client.get("/hello").contentUtf8()).isEqualTo("hello from xds");
        }
    }

    @Test
    void unmanagedPortNotAffected() {
        // ServerExtension's own HTTP port is not managed by XdsServerPlugin.
        // Requests on this port should bypass xDS entirely and serve directly.
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri()).blocking().get("/hello");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello from xds");
    }

    @Test
    void managedPort() {
        // The xDS-managed port goes through xDS and requires TLS.
        final int port = xdsPort.actualPort();
        assertThat(port).isGreaterThan(0);
        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(xdsCert.certificate())
                                                   .build();
        final BlockingWebClient client = WebClient.of("https://127.0.0.1:" + port).blocking();
        final AggregatedHttpResponse res = client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello from xds");
    }
}
