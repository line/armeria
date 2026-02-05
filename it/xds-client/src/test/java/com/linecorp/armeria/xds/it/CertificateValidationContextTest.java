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

import java.io.File;
import java.nio.file.Files;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.CertificateValidationContextSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;

class CertificateValidationContextTest {

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

    private static final String sdsBootstrapYaml =
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
                        tls_certificates:
                          - private_key:
                              filename: %s
                            certificate_chain:
                              filename: %s
                        combined_validation_context:
                          default_validation_context: {}
                          validation_context_sds_secret_config:
                            name: validation-certs
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
    void invalidCaCertificateFile(@TempDir File tempDir) throws Exception {
        final File invalidCaFile = new File(tempDir, "invalid_ca.pem");
        Files.writeString(invalidCaFile.toPath(), "this is not a valid CA certificate");

        final String secretYaml =
                """
                name: validation-certs
                validation_context:
                  trusted_ca:
                    filename: %s
                """.formatted(invalidCaFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr = sdsBootstrapYaml.formatted(
                server.httpPort(),
                certificate1.privateKeyFile().toPath().toString(),
                certificate1.certificateFile().toPath().toString());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            xdsBootstrap.listenerRoot("my-listener").addSnapshotWatcher((snapshot, t) -> {
                if (t != null) {
                    errorRef.set(t);
                }
            });

            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
            assertThat(errorRef.get()).isInstanceOf(CertificateException.class);
        }
    }

    @Test
    void multipleCaCertificates(@TempDir File tempDir) throws Exception {
        final File multiCaFile = new File(tempDir, "multi_ca.pem");
        final String cert1Content = Files.readString(certificate1.certificateFile().toPath());
        final String cert2Content = Files.readString(certificate2.certificateFile().toPath());
        Files.writeString(multiCaFile.toPath(), cert1Content + "\n" + cert2Content);

        final String secretYaml =
                """
                name: validation-certs
                validation_context:
                  trusted_ca:
                    filename: %s
                """.formatted(multiCaFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr = sdsBootstrapYaml.formatted(
                server.httpPort(),
                certificate1.privateKeyFile().toPath().toString(),
                certificate1.certificateFile().toPath().toString());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            xdsBootstrap.listenerRoot("my-listener").addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
            final ListenerSnapshot listenerSnapshot = snapshotRef.get();
            final TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            final CertificateValidationContextSnapshot validationContext =
                    tlsSnapshot.validationContext();
            assertThat(validationContext).isNotNull();
            final List<X509Certificate> trustedCa = validationContext.trustedCa();
            assertThat(trustedCa).hasSize(2);
            assertThat(trustedCa).containsExactlyInAnyOrder(
                    certificate1.certificate(), certificate2.certificate());
        }
    }

    @Test
    void validationContextWithSds(@TempDir File tempDir) throws Exception {
        final File caFile = new File(tempDir, "ca.pem");
        Files.copy(certificate2.certificateFile().toPath(), caFile.toPath());

        final String secretYaml =
                """
                name: validation-certs
                validation_context:
                  trusted_ca:
                    filename: %s
                """.formatted(caFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr = sdsBootstrapYaml.formatted(
                server.httpPort(),
                certificate1.privateKeyFile().toPath().toString(),
                certificate1.certificateFile().toPath().toString());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            xdsBootstrap.listenerRoot("my-listener").addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
            final ListenerSnapshot listenerSnapshot = snapshotRef.get();
            final TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            final CertificateValidationContextSnapshot validationContext =
                    tlsSnapshot.validationContext();
            assertThat(validationContext).isNotNull();
            assertThat(validationContext.trustedCa()).containsExactly(certificate2.certificate());
        }
    }

    private static final String combinedValidationContextBootstrap =
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
                        tls_certificates:
                          - private_key:
                              filename: %s
                            certificate_chain:
                              filename: %s
                        combined_validation_context:
                          default_validation_context:
                            match_subject_alt_names:
                              - exact: "test.example.com"
                          validation_context_sds_secret_config:
                            name: validation-certs
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
    void mergeValidationContextWithBase(@TempDir File tempDir) throws Exception {
        final File caFile = new File(tempDir, "ca.pem");
        Files.copy(certificate2.certificateFile().toPath(), caFile.toPath());

        final String secretYaml =
                """
                name: validation-certs
                validation_context:
                  trusted_ca:
                    filename: %s
                """.formatted(caFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr = combinedValidationContextBootstrap.formatted(
                server.httpPort(),
                certificate1.privateKeyFile().toPath().toString(),
                certificate1.certificateFile().toPath().toString());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            xdsBootstrap.listenerRoot("my-listener").addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
            final ListenerSnapshot listenerSnapshot = snapshotRef.get();
            final TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            final CertificateValidationContextSnapshot validationContext =
                    tlsSnapshot.validationContext();
            assertThat(validationContext).isNotNull();
            assertThat(validationContext.trustedCa()).containsExactly(certificate2.certificate());
            assertThat(validationContext.xdsResource().getMatchSubjectAltNamesList()).hasSize(1);
            assertThat(validationContext.xdsResource().getMatchSubjectAltNames(0).getExact())
                    .isEqualTo("test.example.com");
        }
    }

    @Test
    void overrideBaseContextFields(@TempDir File tempDir) throws Exception {
        final File caFile = new File(tempDir, "ca.pem");
        Files.copy(certificate2.certificateFile().toPath(), caFile.toPath());

        final String secretYaml =
                """
                name: validation-certs
                validation_context:
                  trusted_ca:
                    filename: %s
                  match_subject_alt_names:
                    - exact: "override.example.com"
                """.formatted(caFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(secretYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr = combinedValidationContextBootstrap.formatted(
                server.httpPort(),
                certificate1.privateKeyFile().toPath().toString(),
                certificate1.certificateFile().toPath().toString());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            xdsBootstrap.listenerRoot("my-listener").addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
            final ListenerSnapshot listenerSnapshot = snapshotRef.get();
            final TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            final CertificateValidationContextSnapshot validationContext =
                    tlsSnapshot.validationContext();
            assertThat(validationContext).isNotNull();
            assertThat(validationContext.trustedCa()).containsExactly(certificate2.certificate());
            assertThat(validationContext.xdsResource().getMatchSubjectAltNamesList()).hasSize(2);
            assertThat(validationContext.xdsResource().getMatchSubjectAltNamesList())
                    .anySatisfy(matcher -> assertThat(matcher.getExact()).isEqualTo("test.example.com"))
                    .anySatisfy(matcher -> assertThat(matcher.getExact()).isEqualTo("override.example.com"));
        }
    }
}
