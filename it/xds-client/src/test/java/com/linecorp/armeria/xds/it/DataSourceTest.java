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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
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

class DataSourceTest {

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

    @Test
    void tlsCertificateWithPrivateKeyAndCertificateChain() throws Exception {
        final String tlsCertYaml =
                """
                name: my-cert
                tls_certificate:
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(certificate1.privateKeyFile().toPath().toString(),
                              certificate1.certificateFile().toPath().toString());
        final Secret secret = XdsResourceReader.fromYaml(tlsCertYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr =
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
                """.formatted(server.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
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
            final TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            final TlsCertificateSnapshot certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.xdsResource().getPrivateKey().getFilename())
                    .isEqualTo(certificate1.privateKeyFile().toPath().toString());
            assertThat(certSnapshot.xdsResource().getCertificateChain().getFilename())
                    .isEqualTo(certificate1.certificateFile().toPath().toString());
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());
        }
    }

    @Test
    void certificateFilesChanged(@TempDir File tempDir) throws Exception {
        final File privateKeyFile = new File(tempDir, "private_key.pem");
        final File certificateFile = new File(tempDir, "certificate.pem");

        Files.copy(certificate1.privateKeyFile().toPath(), privateKeyFile.toPath(),
                   StandardCopyOption.REPLACE_EXISTING);
        Files.copy(certificate1.certificateFile().toPath(), certificateFile.toPath(),
                   StandardCopyOption.REPLACE_EXISTING);

        final String tlsCertYaml =
                """
                name: my-cert
                tls_certificate:
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(privateKeyFile.getAbsolutePath(),
                              certificateFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(tlsCertYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr =
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
                """.formatted(server.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
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

            Files.copy(certificate2.privateKeyFile().toPath(), privateKeyFile.toPath(),
                       StandardCopyOption.REPLACE_EXISTING);
            Files.copy(certificate2.certificateFile().toPath(), certificateFile.toPath(),
                       StandardCopyOption.REPLACE_EXISTING);

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

    @Test
    void watchedDirectory(@TempDir File filesDir, @TempDir File watchDir) throws Exception {
        final File privateKeyFile = new File(filesDir, "private_key.pem");
        final File certificateFile = new File(filesDir, "certificate.pem");

        Files.copy(certificate1.privateKeyFile().toPath(), privateKeyFile.toPath(),
                   StandardCopyOption.REPLACE_EXISTING);
        Files.copy(certificate1.certificateFile().toPath(), certificateFile.toPath(),
                   StandardCopyOption.REPLACE_EXISTING);

        final String tlsCertYaml =
                """
                name: my-cert
                tls_certificate:
                  watched_directory:
                    path: %s
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(watchDir.getAbsolutePath(),
                              privateKeyFile.getAbsolutePath(),
                              certificateFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(tlsCertYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr =
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
                """.formatted(server.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
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

            Files.copy(certificate2.privateKeyFile().toPath(), privateKeyFile.toPath(),
                       StandardCopyOption.REPLACE_EXISTING);
            Files.copy(certificate2.certificateFile().toPath(), certificateFile.toPath(),
                       StandardCopyOption.REPLACE_EXISTING);

            await().during(Duration.ofSeconds(1)).untilAsserted(() -> {
                final ListenerSnapshot currentSnapshot = snapshotRef.get();
                assertThat(currentSnapshot).isNotNull();
                final TransportSocketSnapshot currentTlsSnapshot =
                        currentSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                       .routeEntries().get(0).clusterSnapshot().transportSocket();
                final TlsCertificateSnapshot currentCertSnapshot = currentTlsSnapshot.tlsCertificate();
                assertThat(currentCertSnapshot).isNotNull();
                assertThat(currentCertSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());
            });
            listenerSnapshot = snapshotRef.get();
            tlsSnapshot = listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                          .routeEntries().get(0).clusterSnapshot().transportSocket();
            certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());

            final File triggerFile = new File(watchDir, "trigger");
            assertThat(triggerFile.createNewFile()).isTrue();

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

    @Test
    void missingFilesWithoutWatchedDirectory(@TempDir File tempDir) throws Exception {
        final File privateKeyFile = new File(tempDir, "private_key.pem");
        final File certificateFile = new File(tempDir, "certificate.pem");

        final String tlsCertYaml =
                """
                name: my-cert
                tls_certificate:
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(privateKeyFile.getAbsolutePath(),
                              certificateFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(tlsCertYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr =
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
                """.formatted(server.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().during(Duration.ofSeconds(2))
                   .untilAsserted(() -> assertThat(snapshotRef.get()).isNull());

            Files.copy(certificate1.privateKeyFile().toPath(), privateKeyFile.toPath(),
                       StandardCopyOption.REPLACE_EXISTING);
            Files.copy(certificate1.certificateFile().toPath(), certificateFile.toPath(),
                       StandardCopyOption.REPLACE_EXISTING);

            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
            final ListenerSnapshot listenerSnapshot = snapshotRef.get();
            final TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            final TlsCertificateSnapshot certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());
        }
    }

    @Test
    void missingFilesWithWatchedDirectory(@TempDir File filesDir, @TempDir File watchDir) throws Exception {
        final File privateKeyFile = new File(filesDir, "private_key.pem");
        final File certificateFile = new File(filesDir, "certificate.pem");

        final String tlsCertYaml =
                """
                name: my-cert
                tls_certificate:
                  watched_directory:
                    path: %s
                  private_key:
                    filename: %s
                  certificate_chain:
                    filename: %s
                """.formatted(watchDir.getAbsolutePath(),
                              privateKeyFile.getAbsolutePath(),
                              certificateFile.getAbsolutePath());
        final Secret secret = XdsResourceReader.fromYaml(tlsCertYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr =
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
                """.formatted(server.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().during(Duration.ofSeconds(2))
                   .untilAsserted(() -> assertThat(snapshotRef.get()).isNull());

            Files.copy(certificate1.privateKeyFile().toPath(), privateKeyFile.toPath(),
                       StandardCopyOption.REPLACE_EXISTING);
            Files.copy(certificate1.certificateFile().toPath(), certificateFile.toPath(),
                       StandardCopyOption.REPLACE_EXISTING);

            final File triggerFile = new File(watchDir, "trigger");
            assertThat(triggerFile.createNewFile()).isTrue();

            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
            final ListenerSnapshot listenerSnapshot = snapshotRef.get();
            final TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            final TlsCertificateSnapshot certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());
        }
    }

    @Test
    void tlsCertificateWithInlineBytes() throws Exception {
        final byte[] privateKeyBytes = Files.readAllBytes(certificate1.privateKeyFile().toPath());
        final byte[] certBytes = Files.readAllBytes(certificate1.certificateFile().toPath());

        final String tlsCertYaml =
                """
                name: my-cert
                tls_certificate:
                  private_key:
                    inline_bytes: %s
                  certificate_chain:
                    inline_bytes: %s
                """.formatted(Base64.getEncoder().encodeToString(privateKeyBytes),
                              Base64.getEncoder().encodeToString(certBytes));
        final Secret secret = XdsResourceReader.fromYaml(tlsCertYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr =
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
                """.formatted(server.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
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
            final TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            final TlsCertificateSnapshot certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());
        }
    }

    @Test
    void tlsCertificateWithInlineString() throws Exception {
        final String privateKeyContent = Files.readString(certificate1.privateKeyFile().toPath());
        final String certContent = Files.readString(certificate1.certificateFile().toPath());

        final String tlsCertYaml =
                """
                name: my-cert
                tls_certificate:
                  private_key:
                    inline_string: "%s"
                  certificate_chain:
                    inline_string: "%s"
                """.formatted(XdsResourceReader.escapeMultiLine(privateKeyContent),
                              XdsResourceReader.escapeMultiLine(certContent));
        final Secret secret = XdsResourceReader.fromYaml(tlsCertYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr =
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
                """.formatted(server.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
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
            final TransportSocketSnapshot tlsSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot().transportSocket();
            final TlsCertificateSnapshot certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate1.tlsKeyPair());
        }
    }

    @Test
    void emptyDataSource() throws Exception {
        final String tlsCertYaml =
                """
                name: my-cert
                tls_certificate:
                  private_key: {}
                  certificate_chain: {}
                """;
        final Secret secret = XdsResourceReader.fromYaml(tlsCertYaml, Secret.class);
        version.incrementAndGet();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(), ImmutableList.of(secret),
                                                 version.toString()));

        final String bootstrapStr =
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
                """.formatted(server.httpPort());

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapStr, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (t != null) {
                    errorRef.set(t);
                }
            });

            await().untilAsserted(() -> assertThat(errorRef.get()).isNotNull());
        }
    }
}
