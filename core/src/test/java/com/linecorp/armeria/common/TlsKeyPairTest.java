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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;

class TlsKeyPairTest {

    @Test
    void selfSignedIsAValidPair() throws CertificateException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate("foo.com", "RSA", 2048);
        final TlsKeyPair keyPair = TlsKeyPair.of(ssc.key(), ssc.cert());
        assertThat(keyPair.privateKey()).isEqualTo(ssc.key());
        assertThat(keyPair.certificateChain()).containsExactly(ssc.cert());
    }

    @Test
    void ofSelfSignedIsAValidPair() {
        // Must not fail even on a machine whose hostname exceeds the 64-character common name limit
        // of RFC 5280, such as a GitHub Actions macOS runner.
        final TlsKeyPair keyPair = TlsKeyPair.ofSelfSigned();
        assertThat(keyPair.privateKey()).isNotNull();
        final String hostname = SystemInfo.hostname();
        final String expectedCommonName = hostname.length() <= 64 ? hostname : hostname.substring(0, 64);
        assertThat(keyPair.certificateChain().get(0).getSubjectX500Principal().getName())
                .isEqualTo("CN=" + expectedCommonName);
    }

    @Test
    void ecIsAValidPair() throws CertificateException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate("foo.com", "EC", 256);
        final TlsKeyPair keyPair = TlsKeyPair.of(ssc.key(), ssc.cert());
        assertThat(keyPair.certificateChain()).containsExactly(ssc.cert());
    }

    @Test
    void mismatchedKeyOfSameAlgorithmIsRejected() throws CertificateException {
        final SelfSignedCertificate a = new SelfSignedCertificate("foo.com", "RSA", 2048);
        final SelfSignedCertificate b = new SelfSignedCertificate("foo.com", "RSA", 2048);
        assertThatThrownBy(() -> TlsKeyPair.of(a.key(), b.cert()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private key does not match");
    }

    @Test
    void mismatchedEcKeyIsRejected() throws CertificateException {
        final SelfSignedCertificate a = new SelfSignedCertificate("foo.com", "EC", 256);
        final SelfSignedCertificate b = new SelfSignedCertificate("foo.com", "EC", 256);
        assertThatThrownBy(() -> TlsKeyPair.of(a.key(), b.cert()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private key does not match");
    }

    @Test
    void mismatchedKeyAlgorithmIsRejected() throws CertificateException {
        final SelfSignedCertificate rsa = new SelfSignedCertificate("foo.com", "RSA", 2048);
        final SelfSignedCertificate ec = new SelfSignedCertificate("foo.com", "EC", 256);
        assertThatThrownBy(() -> TlsKeyPair.of(rsa.key(), ec.cert()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match the leaf certificate's public key algorithm");
    }

    @Test
    void emptyCertificateChainIsRejected() throws CertificateException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate("foo.com", "RSA", 2048);
        assertThatThrownBy(() -> TlsKeyPair.of(ssc.key()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("certificateChain is empty");
    }

    @Test
    void rsaPssKeyPairIsAccepted() throws Exception {
        // A real RSASSA-PSS key pair must be accepted by the probe instead of having validation silently
        // skipped as if the algorithm were unsupported.
        final KeyPair keyPair = newRsaPssKeyPair();
        assertThat(keyPair.getPublic().getAlgorithm()).isEqualTo("RSASSA-PSS");

        final TlsKeyPair tlsKeyPair = TlsKeyPair.of(keyPair.getPrivate(),
                                                    certificateWithPublicKey(keyPair.getPublic()));
        assertThat(tlsKeyPair.privateKey()).isEqualTo(keyPair.getPrivate());
    }

    @Test
    void mismatchedRsaPssKeyIsRejected() throws Exception {
        // A mismatched RSASSA-PSS pair must be rejected by the probe rather than passing construction because
        // RSASSA-PSS was left out of the recognized signature algorithms.
        final KeyPair a = newRsaPssKeyPair();
        final KeyPair b = newRsaPssKeyPair();
        assertThatThrownBy(() -> TlsKeyPair.of(a.getPrivate(), certificateWithPublicKey(b.getPublic())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private key does not match");
    }

    @Test
    void rsaPssKeyIsAcceptedAgainstRsaCertificate() {
        // A private key reported as RSASSA-PSS belongs to the same family as an RSA certificate's public
        // key, so it must not be rejected as an algorithm-family mismatch.
        final TlsKeyPair keyPair = TlsKeyPair.of(privateKeyWithAlgorithm("RSASSA-PSS"),
                                                 certificateWithPublicKeyAlgorithm("RSA"));
        assertThat(keyPair.privateKey().getAlgorithm()).isEqualTo("RSASSA-PSS");
    }

    @Test
    void validationIsSkippedWhenKeyAlgorithmIsUnavailable() {
        // A provider that returns null from getAlgorithm() must not trip an NPE during validation.
        final TlsKeyPair keyPair = TlsKeyPair.of(privateKeyWithAlgorithm(null),
                                                 certificateWithPublicKeyAlgorithm("RSA"));
        assertThat(keyPair.privateKey().getAlgorithm()).isNull();
    }

    @Test
    void differentEdDsaCurvesAreRejected() {
        // Ed25519 and Ed448 are distinct curves that can never form a key pair, so a specific-curve
        // mismatch must be rejected rather than silently skipped.
        assertThatThrownBy(() -> TlsKeyPair.of(privateKeyWithAlgorithm("Ed25519"),
                                               certificateWithPublicKeyAlgorithm("Ed448")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match the leaf certificate's public key algorithm");
    }

    @Test
    void genericEdDsaMatchesSpecificCurve() {
        // A generic "EdDSA" key must be treated as the same family as a specific curve, so this must not be
        // rejected as a family mismatch. The signature probe cannot complete with a mock key, so validation
        // is skipped and construction succeeds without throwing the family-mismatch error.
        final TlsKeyPair keyPair = TlsKeyPair.of(privateKeyWithAlgorithm("EdDSA"),
                                                 certificateWithPublicKeyAlgorithm("Ed25519"));
        assertThat(keyPair.certificateChain()).hasSize(1);
    }

    private static KeyPair newRsaPssKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSASSA-PSS");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificateWithPublicKey(PublicKey publicKey) {
        // validateKeyPair() only reads the leaf certificate's public key, so a stub that returns the real
        // key is enough to exercise the probe without minting an actual certificate.
        final X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getPublicKey()).thenReturn(publicKey);
        return certificate;
    }

    private static PrivateKey privateKeyWithAlgorithm(@Nullable String algorithm) {
        final PrivateKey key = mock(PrivateKey.class);
        when(key.getAlgorithm()).thenReturn(algorithm);
        return key;
    }

    private static X509Certificate certificateWithPublicKeyAlgorithm(@Nullable String algorithm) {
        final PublicKey publicKey = mock(PublicKey.class);
        when(publicKey.getAlgorithm()).thenReturn(algorithm);
        final X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getPublicKey()).thenReturn(publicKey);
        return certificate;
    }
}
