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

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.xds.CertificateValidationContextSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.TlsCertificateSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class BootstrapSecretsTest {

    @RegisterExtension
    static final SelfSignedCertificateExtension certificate1 = new SelfSignedCertificateExtension();
    @RegisterExtension
    static final SelfSignedCertificateExtension certificate2 = new SelfSignedCertificateExtension();

    //language=YAML
    private static final String staticBootstrap =
            """
                static_resources:
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
                                  port_value: 8080
                      transport_socket:
                        name: envoy.transport_sockets.tls
                        typed_config:
                          "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                          common_tls_context:
                            tls_certificate_sds_secret_configs:
                              - name: my-cert
                            validation_context_sds_secret_config:
                              name: my-validation
                    - name: no-tls-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: no-tls-cluster
                        endpoints:
                        - lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: 8080
                  listeners:
                    - name: my-listener
                      api_listener:
                        api_listener:
                          "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                          stat_prefix: http
                          route_config:
                            name: local_route
                            virtual_hosts:
                            - name: local_service1
                              domains: [ "*" ]
                              routes:
                                - match:
                                    prefix: /
                                  route:
                                    cluster: my-cluster
                                - match:
                                    prefix: /no-tls
                                  route:
                                    cluster: no-tls-cluster
                          http_filters:
                          - name: envoy.filters.http.router
                            typed_config:
                              "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  secrets:
                    - name: my-cert
                      tls_certificate:
                        private_key:
                          filename: %s
                        certificate_chain:
                          filename: %s
                    - name: my-validation
                      validation_context:
                        trusted_ca:
                          filename: %s
                """;

    @Test
    void staticSecretLoaded() throws Exception {
        final String formatted = staticBootstrap.formatted(certificate1.privateKeyFile().toPath().toString(),
                                                           certificate1.certificateFile().toPath().toString(),
                                                           certificate2.certificateFile().toPath().toString());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(formatted);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
            final ListenerSnapshot listenerSnapshot = snapshotRef.get();
            final TransportSocketSnapshot
                    socket1 = listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                              .routeEntries().get(0).clusterSnapshot().transportSocket();
            final TlsCertificateSnapshot certSnapshot = socket1.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());
            final CertificateValidationContextSnapshot validationContext = socket1.validationContext();
            assertThat(validationContext).isNotNull();
            assertThat(validationContext.trustedCa()).containsExactly(certificate2.certificate());

            final TransportSocketSnapshot
                    socket2 = listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                              .routeEntries().get(1).clusterSnapshot().transportSocket();
            assertThat(socket2.validationContext()).isNull();
            assertThat(socket2.tlsCertificate()).isNull();
        }
    }
}
