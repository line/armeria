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
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.it.XdsCertificateExtension;
import com.linecorp.armeria.xds.it.XdsControlPlaneExtension;
import com.linecorp.armeria.xds.it.XdsResourceReader;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.listener.v3.Listener;

class ServerMultiplePluginTest {

    private static final ServerPort port1 =
            new ServerPort(0, SessionProtocol.HTTP, SessionProtocol.HTTPS);
    private static final ServerPort port2 =
            new ServerPort(0, SessionProtocol.HTTP, SessionProtocol.HTTPS);

    @RegisterExtension
    @Order(0)
    static final XdsCertificateExtension cert1 =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(1)
    static final XdsCertificateExtension cert2 =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(2)
    static final XdsControlPlaneExtension controlPlane = new XdsControlPlaneExtension();

    @RegisterExtension
    @Order(3)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            controlPlane.set(buildListener("listener-1", cert1),
                             buildListener("listener-2", cert2));
            sb.plugin(XdsServerPlugin.builder(controlPlane.bootstrap(), "listener-1")
                                     .port(port1)
                                     .build());
            sb.plugin(XdsServerPlugin.builder(controlPlane.bootstrap(), "listener-2")
                                     .port(port2)
                                     .build());
            sb.service("/hello", (ctx, req) -> HttpResponse.of("hello"));
        }
    };

    private static Listener buildListener(String listenerName, XdsCertificateExtension cert) {
        final Path certPath = cert.certificateFile().toPath();
        final Path keyPath = cert.privateKeyFile().toPath();

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
                              filename: '%s'
                            private_key:
                              filename: '%s'
                """.formatted(listenerName, certPath, keyPath);

        return XdsResourceReader.fromYaml(yaml, Listener.class);
    }

    @Test
    void multiplePluginsOnDifferentPorts() {
        final ClientTlsSpec tlsSpec1 = ClientTlsSpec.builder()
                                                    .trustedCertificates(cert1.certificate())
                                                    .build();
        final ClientTlsSpec tlsSpec2 = ClientTlsSpec.builder()
                                                    .trustedCertificates(cert2.certificate())
                                                    .build();

        // port1 = listener-1 = cert1
        final BlockingWebClient client1 =
                WebClient.of("https://127.0.0.1:" + port1.actualPort()).blocking();
        final AggregatedHttpResponse res1 = client1.execute(HttpRequest.of(HttpMethod.GET, "/hello"),
                                                            RequestOptions.builder()
                                                                          .clientTlsSpec(tlsSpec1)
                                                                          .build());
        assertThat(res1.status()).isEqualTo(HttpStatus.OK);
        assertThat(res1.contentUtf8()).isEqualTo("hello");

        // port2 = listener-2 = cert2
        final BlockingWebClient client2 =
                WebClient.of("https://127.0.0.1:" + port2.actualPort()).blocking();
        final AggregatedHttpResponse res2 = client2.execute(HttpRequest.of(HttpMethod.GET, "/hello"),
                                                            RequestOptions.builder()
                                                                          .clientTlsSpec(tlsSpec2)
                                                                          .build());
        assertThat(res2.status()).isEqualTo(HttpStatus.OK);
        assertThat(res2.contentUtf8()).isEqualTo("hello");

        // Wrong cert for port1 should fail.
        final BlockingWebClient client3 =
                WebClient.of("https://127.0.0.1:" + port1.actualPort()).blocking();
        assertThatThrownBy(() -> client3.execute(HttpRequest.of(HttpMethod.GET, "/hello"),
                                                 RequestOptions.builder()
                                                               .clientTlsSpec(tlsSpec2)
                                                               .build()))
                .isInstanceOf(UnprocessedRequestException.class)
                .hasRootCauseInstanceOf(SignatureException.class);
    }
}
