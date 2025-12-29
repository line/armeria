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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.CertificateValidationContextSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.TlsCertificateSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

class DynamicSecretTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final AtomicLong version = new AtomicLong();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension certificate1 = new SelfSignedCertificateExtension();
    @RegisterExtension
    static final SelfSignedCertificateExtension certificate2 = new SelfSignedCertificateExtension();

    // YAML
    private static final String staticBootstrap =
            """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: bootstrap-cluster
                static_resources:
                  clusters:
                    - name: bootstrap-cluster
                      type: STATIC
                      load_assignment:
                        cluster_name: bootstrap-cluster
                        endpoints:
                        - lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: %s
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
                                sds_config:
                                  ads: {}
                            validation_context_sds_secret_config:
                              name: my-validation
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
                          http_filters:
                          - name: envoy.filters.http.router
                            typed_config:
                              "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  secrets:
                    - name: my-validation
                      validation_context:
                        trusted_ca:
                          filename: %s
                """;

    //language=YAML
    private static final String tlsCertYaml =
            """
                name: my-cert
                tls_certificate:
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """;
    //language=YAML
    private static final String validationContextYaml =
            """
                name: my-validation
                validation_context:
                  trusted_ca:
                    filename: %s
                """;

    @Test
    void sdsSecretLoaded() throws Exception {
        final String formattedTlsCert =
                tlsCertYaml.formatted(certificate1.privateKeyFile().toPath().toString(),
                                      certificate1.certificateFile().toPath().toString());
        final Secret secret1 = XdsResourceReader.fromYaml(formattedTlsCert, Secret.class);
        final String formattedValidationContext =
                validationContextYaml.formatted(certificate2.certificateFile().toPath().toString());
        final Secret secret2 = XdsResourceReader.fromYaml(formattedValidationContext, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret1, secret2),
                                                 version.toString()));

        final String formatted = staticBootstrap.formatted(server.httpPort(),
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
                    tlsSnapshot = listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                                  .routeEntries().get(0).clusterSnapshot().transportSocket();
            final TlsCertificateSnapshot certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());
            final CertificateValidationContextSnapshot validationContext = tlsSnapshot.validationContext();
            assertThat(validationContext).isNotNull();
            assertThat(validationContext.trustedCa()).containsExactly(certificate2.certificate());
        }
    }
}
