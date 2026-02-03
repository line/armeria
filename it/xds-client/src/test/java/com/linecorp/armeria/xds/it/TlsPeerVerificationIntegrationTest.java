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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class TlsPeerVerificationIntegrationTest {

    private static final Instant NOT_BEFORE = Instant.now().minus(1, ChronoUnit.DAYS);
    private static final Instant NOT_AFTER = Instant.now().plus(365, ChronoUnit.DAYS);

    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension("localhost");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(serverCert.certificateFile(), serverCert.privateKeyFile());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension ipCert = new SelfSignedCertificateExtension("127.0.0.1");

    @RegisterExtension
    static final ServerExtension ipServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(ipCert.certificateFile(), ipCert.privateKeyFile());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension wildcardCert =
            new SelfSignedCertificateExtension(
                    "localhost",
                    new SecureRandom(),
                    2048,
                    NOT_BEFORE,
                    NOT_AFTER,
                    ImmutableList.of("*.example.com"));

    @RegisterExtension
    static final ServerExtension wildcardServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(wildcardCert.certificateFile(), wildcardCert.privateKeyFile());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension uriCert =
            new SelfSignedCertificateExtension(
                    "localhost",
                    new SecureRandom(),
                    2048,
                    NOT_BEFORE,
                    NOT_AFTER,
                    ImmutableList.of("spiffe://example.com/service"));

    @RegisterExtension
    static final ServerExtension uriServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(uriCert.certificateFile(), uriCert.privateKeyFile());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    // language=YAML
    //language=YAML
    private static final String bootstrapTemplate =
            """
            static_resources:
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
                            port_value: %s
                transport_socket:
                  name: envoy.transport_sockets.tls
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
                    common_tls_context:
                      validation_context:
            %s
            """;

    // language=YAML
    //language=YAML
    private static final String bootstrapTemplateWithoutValidationContext =
            """
            static_resources:
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
                            port_value: %s
                transport_socket:
                  name: envoy.transport_sockets.tls
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
                    common_tls_context: {}
            """;

    @Test
    void requestSucceedsWithPinnedCertificateAndSanMatch() throws Exception {
        final String spkiPin = spkiPin(serverCert.certificate());
        final String certHash = certHash(serverCert.certificate());
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), spkiPin, certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithMatchingSpkiOnly() throws Exception {
        final String spkiPin = spkiPin(serverCert.certificate());
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), spkiPin);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithMatchingCertHashOnly() throws Exception {
        final String certHash = certHash(serverCert.certificate());
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithCertHashWhenSpkiMismatched() throws Exception {
        final String badSpki = mutateLastChar(spkiPin(serverCert.certificate()));
        final String certHash = certHash(serverCert.certificate());
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), badSpki, certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithCaseInsensitiveSanMatch() throws Exception {
        final String spkiPin = spkiPin(serverCert.certificate());
        final String certHash = certHash(serverCert.certificate());
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "LOCALHOST"
                      ignore_case: true
                """.formatted(serverCert.certificateFile().getAbsolutePath(), spkiPin, certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithIpSanMatch() throws Exception {
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                match_typed_subject_alt_names:
                  - san_type: IP_ADDRESS
                    matcher:
                      exact: "127.0.0.1"
                """.formatted(ipCert.certificateFile().getAbsolutePath());
        final String bootstrap = bootstrap(ipServer.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithWildcardDnsSanMatch() throws Exception {
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "api.example.com"
                """.formatted(wildcardCert.certificateFile().getAbsolutePath());
        final String bootstrap = bootstrap(wildcardServer.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithUriSanPrefixMatch() throws Exception {
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                match_typed_subject_alt_names:
                  - san_type: URI
                    matcher:
                      prefix: "spiffe://example.com/"
                """.formatted(uriCert.certificateFile().getAbsolutePath());
        final String bootstrap = bootstrap(uriServer.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithNonExactSanMatchers() throws Exception {
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      prefix: "NOPE"
                      ignore_case: true
                  - san_type: DNS
                    matcher:
                      suffix: "NOPE"
                      ignore_case: true
                  - san_type: DNS
                    matcher:
                      contains: "NOPE"
                      ignore_case: true
                  - san_type: DNS
                    matcher:
                      safe_regex:
                        regex: "local.*"
                """.formatted(serverCert.certificateFile().getAbsolutePath());
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithColonSeparatedCertHash() throws Exception {
        final String certHash = withColons(certHash(serverCert.certificate()));
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestFailsWithMismatchedSpkiPin() throws Exception {
        final String badSpki = mutateLastChar(spkiPin(serverCert.certificate()));
        final String badCertHash = mutateLastChar(certHash(serverCert.certificate()));
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), badSpki, badCertHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> WebClient.builder(preprocessor).build().blocking().get("/"))
                    .isInstanceOf(UnprocessedRequestException.class);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestFailsWithMismatchedSan() throws Exception {
        final String spkiPin = spkiPin(serverCert.certificate());
        final String certHash = certHash(serverCert.certificate());
        //language=YAML
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "invalid.local"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), spkiPin, certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> WebClient.builder(preprocessor).build().blocking().get("/"))
                    .isInstanceOf(UnprocessedRequestException.class);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithNoValidationContextUsesNoVerify() throws Exception {
        final String bootstrap = bootstrapWithoutValidationContext(server.httpsPort());

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final TransportSocketSnapshot tlsSnapshot = transportSocketSnapshot(xdsBootstrap);
            final ClientTlsSpec clientTlsSpec = tlsSnapshot.clientTlsSpec();
            assertThat(clientTlsSpec).isNotNull();
            assertThat(clientTlsSpec.trustedCertificates()).isEmpty();
            assertThat(clientTlsSpec.verifierFactories())
                    .extracting(factory -> factory.getClass().getName())
                    .containsExactly("com.linecorp.armeria.common.NoVerifyPeerVerifierFactory");

            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestSucceedsWithPinsAndSanWithoutTrustedCa() throws Exception {
        final String spkiPin = spkiPin(serverCert.certificate());
        final String certHash = certHash(serverCert.certificate());
        //language=YAML
        final String validationContext =
                """
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(spkiPin, certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final TransportSocketSnapshot tlsSnapshot = transportSocketSnapshot(xdsBootstrap);
            final ClientTlsSpec clientTlsSpec = tlsSnapshot.clientTlsSpec();
            assertThat(clientTlsSpec).isNotNull();
            assertThat(clientTlsSpec.trustedCertificates()).isEmpty();
            assertThat(clientTlsSpec.verifierFactories())
                    .extracting(factory -> factory.getClass().getName())
                    .containsExactly(
                            "com.linecorp.armeria.common.NoVerifyPeerVerifierFactory",
                            "com.linecorp.armeria.xds.PinnedPeerVerifierFactory",
                            "com.linecorp.armeria.xds.SanPeerVerifierFactory");

            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertRequestUsedTls(captor);
        }
    }

    @Test
    void requestFailsWithSystemRootCerts() throws Exception {
        //language=YAML
        final String validationContext =
                """
                system_root_certs: {}
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """;
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final TransportSocketSnapshot tlsSnapshot = transportSocketSnapshot(xdsBootstrap);
            final ClientTlsSpec clientTlsSpec = tlsSnapshot.clientTlsSpec();
            assertThat(clientTlsSpec).isNotNull();
            assertThat(clientTlsSpec.trustedCertificates()).isEmpty();
            assertThat(clientTlsSpec.verifierFactories())
                    .extracting(factory -> factory.getClass().getName())
                    .containsExactly("com.linecorp.armeria.xds.SanPeerVerifierFactory");

            assertThatThrownBy(() -> WebClient.builder(preprocessor).build().blocking().get("/"))
                    .isInstanceOf(UnprocessedRequestException.class);
            assertRequestUsedTls(captor);
        }
    }

    private static void assertRequestUsedTls(ClientRequestContextCaptor captor) {
        final ClientRequestContext ctx = captor.get();
        assertThat(ctx.log().whenComplete().join().sessionProtocol().isTls()).isTrue();
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

    private static String mutateLastChar(String value) {
        final char last = value.charAt(value.length() - 1);
        final char replacement = last == 'A' ? 'B' : 'A';
        return value.substring(0, value.length() - 1) + replacement;
    }

    private static String withColons(String hex) {
        final StringBuilder builder = new StringBuilder(hex.length() + hex.length() / 2);
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(hex, i, Math.min(i + 2, hex.length()));
        }
        return builder.toString();
    }

    private static String bootstrap(int port, String validationContext) {
        return bootstrapTemplate.formatted(port, validationContext.indent(14));
    }

    private static String bootstrapWithoutValidationContext(int port) {
        return bootstrapTemplateWithoutValidationContext.formatted(port);
    }

    private static TransportSocketSnapshot transportSocketSnapshot(XdsBootstrap xdsBootstrap) {
        final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");
        final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
        listenerRoot.addSnapshotWatcher((snapshot, t) -> {
            if (snapshot != null) {
                snapshotRef.set(snapshot);
            }
        });

        await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
        final ListenerSnapshot listenerSnapshot = snapshotRef.get();
        return listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                               .routeEntries().get(0).clusterSnapshot().transportSocket();
    }
}
