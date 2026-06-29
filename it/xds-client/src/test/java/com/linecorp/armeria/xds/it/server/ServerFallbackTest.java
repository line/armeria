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
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLHandshakeException;

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
import com.linecorp.armeria.server.ConnectionAcceptor;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServerTlsProvider;
import com.linecorp.armeria.server.ServerTlsSpec;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.it.XdsCertificateExtension;
import com.linecorp.armeria.xds.it.XdsControlPlaneExtension;
import com.linecorp.armeria.xds.it.XdsResourceReader;
import com.linecorp.armeria.xds.server.XdsServerPlugin;

import io.envoyproxy.envoy.config.listener.v3.Listener;

/**
 * Verifies that {@link XdsServerPlugin} correctly composes with user-configured
 * {@link ConnectionAcceptor} and {@link ServerTlsProvider} on both xDS-managed
 * and unmanaged ports.
 */
class ServerFallbackTest {

    private static final String LISTENER_NAME = "fallback-listener";
    private static final ServerPort xdsPort =
            new ServerPort(0, SessionProtocol.HTTP, SessionProtocol.HTTPS);

    private static final AtomicInteger acceptorCallCount = new AtomicInteger();

    @RegisterExtension
    @Order(0)
    static final XdsCertificateExtension xdsCert =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("127.0.0.1"));

    @RegisterExtension
    @Order(1)
    static final SelfSignedCertificateExtension userCert =
            new SelfSignedCertificateExtension("127.0.0.1");

    @RegisterExtension
    @Order(2)
    static final XdsControlPlaneExtension controlPlane = new XdsControlPlaneExtension();

    @RegisterExtension
    @Order(3)
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

            // User-configured ConnectionAcceptor — should be called on all ports.
            sb.connectionAcceptor(ConnectionAcceptor.of(ctx -> {
                acceptorCallCount.incrementAndGet();
                return true;
            }));

            // User-configured ServerTlsProvider for the unmanaged HTTPS port.
            final ServerTlsSpec userTlsSpec = ServerTlsSpec.builder()
                                                           .tlsKeyPair(userCert.tlsKeyPair())
                                                           .build();
            sb.tlsProvider(ServerTlsProvider.of(ctx -> userTlsSpec));

            // Add an explicit unmanaged HTTPS port (not controlled by xDS).
            sb.https(0);

            sb.plugin(XdsServerPlugin.builder(controlPlane.bootstrap(), LISTENER_NAME)
                                     .port(xdsPort)
                                     .build());
            sb.service("/hello", (ctx, req) -> HttpResponse.of("hello"));
        }
    };

    @Test
    void connectionAcceptorCalledOnManagedPort() {
        acceptorCallCount.set(0);
        final ClientTlsSpec tlsSpec = ClientTlsSpec.builder()
                                                   .trustedCertificates(xdsCert.certificate())
                                                   .build();
        final BlockingWebClient client =
                WebClient.of("https://127.0.0.1:" + xdsPort.actualPort()).blocking();
        final AggregatedHttpResponse res = client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(tlsSpec).build());
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello");
        assertThat(acceptorCallCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void connectionAcceptorCalledOnUnmanagedPort() {
        acceptorCallCount.set(0);
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri()).blocking().get("/hello");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello");
        assertThat(acceptorCallCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void tlsProviderFallbackOnUnmanagedPort() {
        // The unmanaged HTTPS port should use the user-configured TLS provider (userCert),
        // not the xDS certificate.
        final int unmanagedHttpsPort = server.server().activePorts().values().stream()
                                             .filter(ServerPort::hasHttps)
                                             .map(p -> p.localAddress().getPort())
                                             .filter(p -> p != xdsPort.actualPort())
                                             .findFirst()
                                             .orElseThrow();
        final ClientTlsSpec userTlsSpec = ClientTlsSpec.builder()
                                                       .trustedCertificates(userCert.certificate())
                                                       .build();
        final BlockingWebClient client =
                WebClient.of("https://127.0.0.1:" + unmanagedHttpsPort).blocking();
        final AggregatedHttpResponse res = client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(userTlsSpec).build());
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("hello");
    }

    @Test
    void xdsCertNotTrustedOnUnmanagedPort() {
        // The xDS certificate should NOT work on the unmanaged port — that port uses
        // userCert, so trusting xdsCert should fail the TLS handshake.
        final int unmanagedHttpsPort = server.server().activePorts().values().stream()
                                             .filter(ServerPort::hasHttps)
                                             .map(p -> p.localAddress().getPort())
                                             .filter(p -> p != xdsPort.actualPort())
                                             .findFirst()
                                             .orElseThrow();
        final ClientTlsSpec xdsTlsSpec = ClientTlsSpec.builder()
                                                      .trustedCertificates(xdsCert.certificate())
                                                      .build();
        final BlockingWebClient client =
                WebClient.of("https://127.0.0.1:" + unmanagedHttpsPort).blocking();
        assertThatThrownBy(() -> client.execute(
                HttpRequest.of(HttpMethod.GET, "/hello"),
                RequestOptions.builder().clientTlsSpec(xdsTlsSpec).build()))
                .hasCauseInstanceOf(SSLHandshakeException.class);
    }
}
