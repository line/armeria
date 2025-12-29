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

import java.util.List;
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
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.TlsCertificateSnapshot;
import com.linecorp.armeria.xds.TransportSocketMatchSnapshot;
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
                                  .addService(v3DiscoveryServer.getSecretDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension certificate1 = new SelfSignedCertificateExtension();
    @RegisterExtension
    static final SelfSignedCertificateExtension certificate2 = new SelfSignedCertificateExtension();
    @RegisterExtension
    static final SelfSignedCertificateExtension certificate3 = new SelfSignedCertificateExtension();
    @RegisterExtension
    static final SelfSignedCertificateExtension certificate4 = new SelfSignedCertificateExtension();

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

    @Test
    void sdsSecretLoadedWithAds() throws Exception {
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

    private static final String explicitConfigSourceBootstrap =
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
                                  api_config_source:
                                    api_type: GRPC
                                    grpc_services:
                                      - envoy_grpc:
                                          cluster_name: bootstrap-cluster
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

    @Test
    void sdsSecretLoadedWithExplicitConfigSource() throws Exception {
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

        final String formatted = explicitConfigSourceBootstrap.formatted(
                server.httpPort(),
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

    private static final String tlsCertOnlyBootstrap =
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
                """;

    @Test
    void sdsTlsCertificateOnly() throws Exception {
        final String formattedTlsCert =
                tlsCertYaml.formatted(certificate1.privateKeyFile().toPath().toString(),
                                      certificate1.certificateFile().toPath().toString());
        final Secret secret1 = XdsResourceReader.fromYaml(formattedTlsCert, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret1),
                                                 version.toString()));

        final String formatted = tlsCertOnlyBootstrap.formatted(server.httpPort());
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
            assertThat(validationContext.trustedCa()).isNull();
        }
    }

    private static final String validationContextOnlyBootstrap =
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
                            validation_context_sds_secret_config:
                              name: my-cert-validation
                              sds_config:
                                ads: {}
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
                """;

    @Test
    void sdsValidationContextOnly() throws Exception {
        final String validationYaml =
                """
                name: my-cert-validation
                validation_context:
                  trusted_ca:
                    filename: %s
                """.formatted(certificate2.certificateFile().toPath().toString());
        final Secret secret2 = XdsResourceReader.fromYaml(validationYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret2),
                                                 version.toString()));

        final String formatted = validationContextOnlyBootstrap.formatted(server.httpPort());
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
            assertThat(certSnapshot.tlsKeyPair()).isNull();
            final CertificateValidationContextSnapshot validationContext = tlsSnapshot.validationContext();
            assertThat(validationContext).isNotNull();
            assertThat(validationContext.trustedCa()).containsExactly(certificate2.certificate());
        }
    }

    private static final String dynamicOverridesStaticBootstrap =
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
                    - name: my-cert
                      tls_certificate:
                        private_key:
                          filename: %s
                        certificate_chain:
                          filename: %s
                """;

    @Test
    void dynamicSecretOverridesStaticSecret() throws Exception {
        final String formattedDynamicCert =
                tlsCertYaml.formatted(certificate1.privateKeyFile().toPath().toString(),
                                      certificate1.certificateFile().toPath().toString());
        final Secret dynamicSecret = XdsResourceReader.fromYaml(formattedDynamicCert, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(dynamicSecret),
                                                 version.toString()));

        final String formatted = dynamicOverridesStaticBootstrap.formatted(
                server.httpPort(),
                certificate3.privateKeyFile().toPath().toString(),
                certificate3.certificateFile().toPath().toString());
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
            assertThat(certSnapshot.tlsKeyPair()).isNotEqualTo(certificate3.tlsKeyPair());
        }
    }

    private static final String transportSocketMatchesBootstrap =
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
                              - name: default-cert
                                sds_config:
                                  ads: {}
                      transport_socket_matches:
                        - name: match1
                          match:
                            env: prod
                          transport_socket:
                            name: envoy.transport_sockets.tls
                            typed_config:
                              "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                              common_tls_context:
                                tls_certificate_sds_secret_configs:
                                  - name: match1-cert
                                    sds_config:
                                      ads: {}
                        - name: match2
                          match:
                            env: staging
                          transport_socket:
                            name: envoy.transport_sockets.tls
                            typed_config:
                              "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                .tls.v3.UpstreamTlsContext
                              common_tls_context:
                                tls_certificate_sds_secret_configs:
                                  - name: match2-cert
                                    sds_config:
                                      ads: {}
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
                """;

    @Test
    void transportSocketMatchesLoaded() throws Exception {
        final String defaultCertYaml =
                tlsCertYaml.formatted(certificate1.privateKeyFile().toPath().toString(),
                                      certificate1.certificateFile().toPath().toString())
                           .replace("my-cert", "default-cert");
        final Secret defaultSecret = XdsResourceReader.fromYaml(defaultCertYaml, Secret.class);

        final String match1CertYaml =
                tlsCertYaml.formatted(certificate2.privateKeyFile().toPath().toString(),
                                      certificate2.certificateFile().toPath().toString())
                           .replace("my-cert", "match1-cert");
        final Secret match1Secret = XdsResourceReader.fromYaml(match1CertYaml, Secret.class);

        final String match2CertYaml =
                tlsCertYaml.formatted(certificate3.privateKeyFile().toPath().toString(),
                                      certificate3.certificateFile().toPath().toString())
                           .replace("my-cert", "match2-cert");
        final Secret match2Secret = XdsResourceReader.fromYaml(match2CertYaml, Secret.class);

        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(),
                                                 ImmutableList.of(defaultSecret, match1Secret, match2Secret),
                                                 version.toString()));

        final String formatted = transportSocketMatchesBootstrap.formatted(server.httpPort());
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
            final ClusterSnapshot clusterSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot();

            final TransportSocketSnapshot defaultSocket = clusterSnapshot.transportSocket();
            assertThat(defaultSocket.tlsCertificate()).isNotNull();
            assertThat(defaultSocket.tlsCertificate().tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());

            final List<TransportSocketMatchSnapshot> matches = clusterSnapshot.transportSocketMatches();
            assertThat(matches).hasSize(2);

            final TransportSocketMatchSnapshot match1 = matches.get(0);
            assertThat(match1.xdsResource().getName()).isEqualTo("match1");
            assertThat(match1.transportSocket().tlsCertificate()).isNotNull();
            assertThat(match1.transportSocket().tlsCertificate().tlsKeyPair())
                    .isEqualTo(certificate2.tlsKeyPair());

            final TransportSocketMatchSnapshot match2 = matches.get(1);
            assertThat(match2.xdsResource().getName()).isEqualTo("match2");
            assertThat(match2.transportSocket().tlsCertificate()).isNotNull();
            assertThat(match2.transportSocket().tlsCertificate().tlsKeyPair())
                    .isEqualTo(certificate3.tlsKeyPair());
        }
    }

    @Test
    void secretUpdatedDynamically() throws Exception {
        final String initialCertYaml =
                tlsCertYaml.formatted(certificate1.privateKeyFile().toPath().toString(),
                                      certificate1.certificateFile().toPath().toString());
        final Secret initialSecret = XdsResourceReader.fromYaml(initialCertYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(initialSecret),
                                                 version.toString()));

        final String formatted = tlsCertOnlyBootstrap.formatted(server.httpPort());
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
            ListenerSnapshot listenerSnapshot = snapshotRef.get();
            TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            TlsCertificateSnapshot certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());

            final String updatedCertYaml =
                    tlsCertYaml.formatted(certificate2.privateKeyFile().toPath().toString(),
                                          certificate2.certificateFile().toPath().toString());
            final Secret updatedSecret = XdsResourceReader.fromYaml(updatedCertYaml, Secret.class);
            version.incrementAndGet();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                     ImmutableList.of(), ImmutableList.of(updatedSecret),
                                                     version.toString()));

            await().untilAsserted(() -> {
                final ListenerSnapshot currentSnapshot = snapshotRef.get();
                assertThat(currentSnapshot).isNotNull();
                final TransportSocketSnapshot currentTlsSnapshot =
                        currentSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                       .routeEntries().get(0).clusterSnapshot().transportSocket();
                final TlsCertificateSnapshot currentCertSnapshot = currentTlsSnapshot.tlsCertificate();
                assertThat(currentCertSnapshot).isNotNull();
                assertThat(currentCertSnapshot.tlsKeyPair()).isEqualTo(certificate2.tlsKeyPair());
            });

            listenerSnapshot = snapshotRef.get();
            tlsSnapshot = listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                          .routeEntries().get(0).clusterSnapshot().transportSocket();
            certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate2.tlsKeyPair());
            assertThat(certSnapshot.tlsKeyPair()).isNotEqualTo(certificate1.tlsKeyPair());
        }
    }
}
