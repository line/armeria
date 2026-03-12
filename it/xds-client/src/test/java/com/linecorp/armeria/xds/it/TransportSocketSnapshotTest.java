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

import static com.linecorp.armeria.xds.it.XdsResourceReader.escapeMultiLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.xds.CertificateValidationContextSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.TlsCertificateSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class TransportSocketSnapshotTest {

    @RegisterExtension
    static SelfSignedCertificateExtension certificate = new SelfSignedCertificateExtension();

    //language=YAML
    private static final String tlsCertBootstrap =
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
                            tls_certificates:
                              - private_key:
                                  %s
                                certificate_chain:
                                  %s
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

    static Stream<Arguments> testDataSources() throws Exception {
        final Encoder encoder = Base64.getEncoder();
        return Stream.of(
                Arguments.of("inline_string: \"%s\"",
                             (SecretProvider) file -> escapeMultiLine(Files.readString(file.toPath())),
                             (SecretProvider) file -> escapeMultiLine(Files.readString(file.toPath()))),
                Arguments.of("inline_bytes: \"%s\"",
                             (SecretProvider) file -> encoder.encodeToString(Files.readAllBytes(file.toPath())),
                             (SecretProvider) file -> encoder.encodeToString(Files.readAllBytes(file.toPath()))
                ),
                Arguments.of("filename: '%s'",
                             (SecretProvider) File::getAbsolutePath,
                             (SecretProvider) File::getAbsolutePath),
                Arguments.of("filename: '%s'",
                             (SecretProvider) file -> relativizeToCwd(file).toString(),
                             (SecretProvider) file -> relativizeToCwd(file).toString())
        );
    }

    @ParameterizedTest
    @MethodSource("testDataSources")
    void staticTlsCertificate(String secretYamlTemplate, SecretProvider keyProvider,
                              SecretProvider certProvider) throws Exception {
        final String serviceKey = keyProvider.getSecret(certificate.privateKeyFile());
        final String serviceCert = certProvider.getSecret(certificate.certificateFile());
        final String bootstrapStr = tlsCertBootstrap.formatted(secretYamlTemplate.formatted(serviceKey),
                                                               secretYamlTemplate.formatted(serviceCert));
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
            final TransportSocketSnapshot
                    tlsSnapshot = listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                                  .routeEntries().get(0).clusterSnapshot().transportSocket();
            final TlsCertificateSnapshot certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate.tlsKeyPair());
        }
    }

    //language=YAML
    private static final String validationContextBootstrap =
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
                            validation_context:
                              trusted_ca:
                                %s
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

    @ParameterizedTest
    @MethodSource("testDataSources")
    void staticValidationContext(String secretYamlTemplate, SecretProvider keyProvider,
                                 SecretProvider certProvider) throws Exception {
        final String caCert = certProvider.getSecret(certificate.certificateFile());
        final String bootstrapStr = validationContextBootstrap.formatted(secretYamlTemplate.formatted(caCert));
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
            final TransportSocketSnapshot
                    tlsSnapshot = listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                                  .routeEntries().get(0).clusterSnapshot().transportSocket();
            final CertificateValidationContextSnapshot validationContext = tlsSnapshot.validationContext();
            assertThat(validationContext).isNotNull();
            assertThat(validationContext.trustedCa()).containsExactly(certificate.certificate());
        }
    }

    //language=YAML
    private static final String combinedBootstrap =
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
                            tls_certificates:
                              - private_key:
                                  %s
                                certificate_chain:
                                  %s
                            validation_context:
                              trusted_ca:
                                %s
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

    @ParameterizedTest
    @MethodSource("testDataSources")
    void staticCombined(String secretYamlTemplate, SecretProvider keyProvider,
                        SecretProvider certProvider) throws Exception {
        final String serviceKey = keyProvider.getSecret(certificate.privateKeyFile());
        final String serviceCert = certProvider.getSecret(certificate.certificateFile());
        final String caCert = certProvider.getSecret(certificate.certificateFile());
        final String bootstrapStr = combinedBootstrap.formatted(secretYamlTemplate.formatted(serviceKey),
                                                                secretYamlTemplate.formatted(serviceCert),
                                                                secretYamlTemplate.formatted(caCert));
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
            final TransportSocketSnapshot
                    tlsSnapshot = listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                                  .routeEntries().get(0).clusterSnapshot().transportSocket();
            final TlsCertificateSnapshot certSnapshot = tlsSnapshot.tlsCertificate();
            assertThat(certSnapshot).isNotNull();
            assertThat(certSnapshot.tlsKeyPair()).isEqualTo(certificate.tlsKeyPair());

            final CertificateValidationContextSnapshot validationContext = tlsSnapshot.validationContext();
            assertThat(validationContext).isNotNull();
            assertThat(validationContext.trustedCa()).containsExactly(certificate.certificate());
        }
    }

    //language=YAML
    private static final String validationContextWithPinsBootstrap =
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
                            tls_certificates:
                              - private_key:
                                  %s
                                certificate_chain:
                                  %s
                            validation_context:
                              trusted_ca:
                                %s
                              verify_certificate_spki:
                                - "%s"
                              verify_certificate_hash:
                                - "%s"
                              match_typed_subject_alt_names:
                                - san_type: DNS
                                  matcher:
                                    exact: "localhost"
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

    @ParameterizedTest
    @MethodSource("testDataSources")
    void clientTlsSpecIncludesPinnedAndSanVerifiers(String secretYamlTemplate, SecretProvider keyProvider,
                                                    SecretProvider certProvider) throws Exception {
        final String serviceKey = keyProvider.getSecret(certificate.privateKeyFile());
        final String serviceCert = certProvider.getSecret(certificate.certificateFile());
        final String caCert = certProvider.getSecret(certificate.certificateFile());
        final String spkiPin = spkiPin(certificate.certificate());
        final String certHash = certHash(certificate.certificate());
        final String bootstrapStr =
                validationContextWithPinsBootstrap.formatted(secretYamlTemplate.formatted(serviceKey),
                                                             secretYamlTemplate.formatted(serviceCert),
                                                             secretYamlTemplate.formatted(caCert),
                                                             spkiPin,
                                                             certHash);
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
            final ClientTlsSpec clientTlsSpec = tlsSnapshot.clientTlsSpec();
            assertThat(clientTlsSpec).isNotNull();
            assertThat(clientTlsSpec.trustedCertificates()).containsExactly(certificate.certificate());
            assertThat(clientTlsSpec.verifierFactories())
                    .hasSize(2)
                    .extracting(factory -> factory.getClass().getName())
                    .containsExactlyInAnyOrder(
                            "com.linecorp.armeria.xds.PinnedPeerVerifierFactory",
                            "com.linecorp.armeria.xds.SanPeerVerifierFactory");
        }
    }

    @ParameterizedTest
    @MethodSource("testDataSources")
    void clientTlsSpecUsesNoVerifyWithoutValidationContext(
            String secretYamlTemplate, SecretProvider keyProvider, SecretProvider certProvider)
            throws Exception {
        final String serviceKey = keyProvider.getSecret(certificate.privateKeyFile());
        final String serviceCert = certProvider.getSecret(certificate.certificateFile());
        final String bootstrapStr = tlsCertBootstrap.formatted(secretYamlTemplate.formatted(serviceKey),
                                                               secretYamlTemplate.formatted(serviceCert));
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
            final ClientTlsSpec clientTlsSpec = tlsSnapshot.clientTlsSpec();
            assertThat(clientTlsSpec).isNotNull();
            assertThat(clientTlsSpec.trustedCertificates()).isEmpty();
            assertThat(clientTlsSpec.verifierFactories())
                    .extracting(factory -> factory.getClass().getName())
                    .containsExactly("com.linecorp.armeria.common.NoVerifyPeerVerifierFactory");
        }
    }

    @FunctionalInterface
    interface SecretProvider {
        String getSecret(File file) throws Exception;
    }

    static Path relativizeToCwd(File file) {
        final Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        final Path abs = file.toPath().toAbsolutePath().normalize();

        final Path cwdRoot = cwd.getRoot();
        final Path absRoot = abs.getRoot();
        if (cwdRoot != null && absRoot != null && !cwdRoot.equals(absRoot)) {
            return abs;
        }
        if (cwd.isAbsolute() != abs.isAbsolute()) {
            return abs;
        }
        return cwd.relativize(abs);
    }

    private static String spkiPin(X509Certificate certificate) throws CertificateException {
        final byte[] digest = sha256(certificate.getPublicKey().getEncoded());
        return Base64.getEncoder().encodeToString(digest);
    }

    private static String certHash(X509Certificate certificate) throws CertificateException {
        final byte[] digest = sha256(certificate.getEncoded());
        return BaseEncoding.base16().lowerCase().encode(digest);
    }

    private static byte[] sha256(byte[] input) throws CertificateException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException("SHA-256 is not available.", e);
        }
    }
}
