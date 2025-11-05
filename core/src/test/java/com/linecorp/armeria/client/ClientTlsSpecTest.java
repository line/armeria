/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import javax.net.ssl.KeyManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;

class ClientTlsSpecTest {

    @RegisterExtension
    static final SelfSignedCertificateExtension clientCert = new SelfSignedCertificateExtension();

    @Test
    void ciphersValidation() throws Exception {
        assertThatThrownBy(() -> ClientTlsSpec.builder().ciphers().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("At least one cipher must be specified.");
        assertThatThrownBy(() -> ClientTlsSpec.builder().ciphers((Iterable<String>) null)
                                              .build())
                .isInstanceOf(NullPointerException.class);

        final Set<String> customCiphers =
                ImmutableSet.of("TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256");
        assertThat(ClientTlsSpec.builder().ciphers(customCiphers).build().ciphers())
                .containsExactlyInAnyOrderElementsOf(customCiphers);
    }

    @Test
    void keyPairValidation() throws Exception {
        final TlsKeyPair keyPair = clientCert.tlsKeyPair();
        final KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        assertThatThrownBy(() -> ClientTlsSpec.builder()
                                              .tlsKeyPair(keyPair)
                                              .keyManagerFactory(keyManagerFactory)
                                              .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'tlsKeyPair' and 'keyManagerFactory' cannot both be set");
    }

    @Test
    void tlsKeyPairProperty() {
        final TlsKeyPair keyPair = clientCert.tlsKeyPair();
        final ClientTlsSpec spec = ClientTlsSpec.builder()
                                                .tlsKeyPair(keyPair)
                                                .build();
        assertThat(spec.tlsKeyPair()).isEqualTo(keyPair);
        assertThat(spec.keyManagerFactory()).isNull();
    }

    @Test
    void keyManagerFactoryProperty() throws Exception {
        final KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        final ClientTlsSpec spec = ClientTlsSpec.builder()
                                                .keyManagerFactory(keyManagerFactory)
                                                .build();

        assertThat(spec.keyManagerFactory()).isEqualTo(keyManagerFactory);
        assertThat(spec.tlsKeyPair()).isNull();
    }

    @Test
    void trustedCertificatesProperty() {
        final X509Certificate cert = clientCert.certificate();

        // Test with certificates
        ClientTlsSpec spec = ClientTlsSpec.builder()
                                          .trustedCertificates(cert)
                                          .build();
        assertThat(spec.trustedCertificates()).containsExactly(cert);

        // Test with iterable
        final List<X509Certificate> certs = ImmutableList.of(cert);
        spec = ClientTlsSpec.builder()
                            .trustedCertificates(certs)
                            .build();
        assertThat(spec.trustedCertificates()).containsExactlyElementsOf(certs);

        // Test validation - null certificates not allowed
        assertThatThrownBy(() -> ClientTlsSpec.builder()
                                              .trustedCertificates((List<X509Certificate>) null)
                                              .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void verifierFactoriesProperty() {
        final TlsPeerVerifierFactory factory = TlsPeerVerifierFactory.noVerify();

        // Test with factory
        ClientTlsSpec spec = ClientTlsSpec.builder()
                                          .verifierFactories(factory)
                                          .build();
        assertThat(spec.verifierFactories()).containsExactly(factory);

        // Test with iterable
        final List<TlsPeerVerifierFactory> factories = ImmutableList.of(factory);
        spec = ClientTlsSpec.builder()
                            .verifierFactories(factories)
                            .build();
        assertThat(spec.verifierFactories()).containsExactlyElementsOf(factories);

        // Test validation - empty verifier factories not allowed when specified
        assertThatThrownBy(() -> ClientTlsSpec.builder()
                                              .verifierFactories((List<TlsPeerVerifierFactory>) null)
                                              .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void engineTypeProperty() {
        final ClientTlsSpec spec = ClientTlsSpec.builder()
                                                .engineType(TlsEngineType.OPENSSL)
                                                .build();
        assertThat(spec.engineType()).isEqualTo(TlsEngineType.OPENSSL);
    }

    @Test
    void isServerProperty() {
        final ClientTlsSpec spec = ClientTlsSpec.builder().build();
        assertThat(spec.isServer()).isFalse();
    }

    @Test
    void toBuilderPreservesProperties() {
        final TlsKeyPair keyPair = clientCert.tlsKeyPair();
        final X509Certificate cert = clientCert.certificate();
        final TlsPeerVerifierFactory factory = TlsPeerVerifierFactory.noVerify();
        final Set<String> customCiphers = ImmutableSet.of("TLS_AES_256_GCM_SHA384");

        final ClientTlsSpec original = ClientTlsSpec.builder()
                                                    .tlsKeyPair(keyPair)
                                                    .trustedCertificates(cert)
                                                    .verifierFactories(factory)
                                                    .engineType(TlsEngineType.OPENSSL)
                                                    .ciphers(customCiphers)
                                                    .build();

        final ClientTlsSpec rebuilt = original.toBuilder().build();

        assertThat(rebuilt.tlsKeyPair()).isEqualTo(original.tlsKeyPair());
        assertThat(rebuilt.trustedCertificates()).isEqualTo(original.trustedCertificates());
        assertThat(rebuilt.verifierFactories()).isEqualTo(original.verifierFactories());
        assertThat(rebuilt.engineType()).isEqualTo(original.engineType());
        assertThat(rebuilt.ciphers()).isEqualTo(original.ciphers());
        assertThat(rebuilt.protocols()).isEqualTo(original.protocols());
        assertThat(rebuilt.alpnProtocols()).isEqualTo(original.alpnProtocols());
    }
}
