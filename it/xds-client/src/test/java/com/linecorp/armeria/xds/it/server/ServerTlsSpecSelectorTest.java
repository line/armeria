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
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.it.XdsCertificateExtension;
import com.linecorp.armeria.xds.it.XdsControlPlaneExtension;
import com.linecorp.armeria.xds.it.XdsResourceReader;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.listener.v3.Listener;

class ServerTlsSpecSelectorTest {

    private static final String LISTENER_NAME = "sni-listener";

    @RegisterExtension
    @Order(0)
    static final XdsCertificateExtension certFoo =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("foo.example.com"));

    @RegisterExtension
    @Order(1)
    static final XdsCertificateExtension certBar =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("bar.example.com"));

    @RegisterExtension
    @Order(2)
    static final XdsControlPlaneExtension controlPlane = new XdsControlPlaneExtension();

    @RegisterExtension
    @Order(3)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final Path certPathFoo = certFoo.certificateFile().toPath();
            final Path keyPathFoo = certFoo.privateKeyFile().toPath();
            final Path certPathBar = certBar.certificateFile().toPath();
            final Path keyPathBar = certBar.privateKeyFile().toPath();

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
                              - certificate_chain:
                                  filename: '%s'
                                private_key:
                                  filename: '%s'
                    """.formatted(LISTENER_NAME, certPathFoo, keyPathFoo, certPathBar, keyPathBar);
            controlPlane.set(XdsResourceReader.fromYaml(yaml, Listener.class));
            sb.plugin(XdsServerPlugin.of(controlPlane.bootstrap(), LISTENER_NAME));
            sb.service("/hello", (ctx, req) -> HttpResponse.of("hello"));
        }
    };

    @Test
    void exactSniMatch() {
        final int port = server.httpsPort();

        // SNI "foo.example.com" → certFoo should be presented.
        // Trust only certFoo: if certBar were presented, the handshake would fail.
        final ClientTlsSpec fooTlsSpec = ClientTlsSpec.builder()
                                                      .trustedCertificates(certFoo.certificate())
                                                      .build();
        final Endpoint fooEndpoint = Endpoint.of("foo.example.com", port).withIpAddr("127.0.0.1");
        final BlockingWebClient fooClient =
                WebClient.builder(SessionProtocol.HTTPS, fooEndpoint).build().blocking();
        final AggregatedHttpResponse fooRes = fooClient.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(fooTlsSpec).build());
        assertThat(fooRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(fooRes.contentUtf8()).isEqualTo("hello");

        // SNI "bar.example.com" → certBar should be presented.
        // Trust only certBar: if certFoo were presented, the handshake would fail.
        final ClientTlsSpec barTlsSpec = ClientTlsSpec.builder()
                                                      .trustedCertificates(certBar.certificate())
                                                      .build();
        final Endpoint barEndpoint = Endpoint.of("bar.example.com", port).withIpAddr("127.0.0.1");
        final BlockingWebClient barClient =
                WebClient.builder(SessionProtocol.HTTPS, barEndpoint).build().blocking();
        final AggregatedHttpResponse barRes = barClient.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(barTlsSpec).build());
        assertThat(barRes.status()).isEqualTo(HttpStatus.OK);
        assertThat(barRes.contentUtf8()).isEqualTo("hello");
    }

    @Test
    void fallbackToFirstCert() {
        // Unknown SNI → first cert (certFoo) should be presented.
        // Trust only certFoo, disable hostname verification since the cert CN
        // (foo.example.com) won't match the SNI (unknown.example.com).
        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(certFoo.certificate())
                                                   .endpointIdentificationAlgorithm("")
                                                   .build();
        final int port = server.httpsPort();
        final Endpoint endpoint = Endpoint.of("unknown.example.com", port).withIpAddr("127.0.0.1");
        final BlockingWebClient client =
                WebClient.builder(SessionProtocol.HTTPS, endpoint).build().blocking();
        final AggregatedHttpResponse res = client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello");
    }

    @Test
    void noSniReturnsFirstCert() {
        // Connect by IP (127.0.0.1) — no SNI hostname is sent.
        // Server should present the first cert (certFoo).
        // Disable hostname verification since the cert CN (foo.example.com) won't match 127.0.0.1.
        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(certFoo.certificate())
                                                   .endpointIdentificationAlgorithm("")
                                                   .build();
        final BlockingWebClient client = WebClient.of(server.httpsUri()).blocking();
        final AggregatedHttpResponse res = client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello");
    }
}
