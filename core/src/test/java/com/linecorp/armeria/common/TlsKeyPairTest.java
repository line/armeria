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

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.annotation.Nullable;
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
        assertThat(TlsKeyPair.ofSelfSigned().privateKey()).isNotNull();
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
        // An RSASSA-PSS key and its matching certificate share the same underlying RSA key material, so the
        // probe must accept them instead of silently skipping validation for an "unsupported" algorithm.
        final KeyPair keyPair = newRsaPssKeyPair();
        final X509Certificate certificate = newRsaPssCertificate(keyPair);
        assertThat(certificate.getPublicKey().getAlgorithm()).isEqualTo("RSASSA-PSS");

        final TlsKeyPair keyPairResult = TlsKeyPair.of(keyPair.getPrivate(), certificate);
        assertThat(keyPairResult.certificateChain()).containsExactly(certificate);
    }

    @Test
    void mismatchedRsaPssKeyIsRejected() throws Exception {
        // A mismatched RSASSA-PSS pair must be rejected by the probe rather than passing construction because
        // RSASSA-PSS was left out of the recognized signature algorithms.
        final KeyPair a = newRsaPssKeyPair();
        final KeyPair b = newRsaPssKeyPair();
        final X509Certificate certificateB = newRsaPssCertificate(b);
        assertThatThrownBy(() -> TlsKeyPair.of(a.getPrivate(), certificateB))
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

    private static X509Certificate newRsaPssCertificate(KeyPair keyPair) throws Exception {
        final X500Name name = new X500Name("CN=rsapss.test");
        final Instant now = Instant.now();
        final PSSParameterSpec pssSpec =
                new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
        // BouncyCastle is required to resolve the RSASSA-PSS signature; pass the provider explicitly rather
        // than registering it globally to avoid affecting other tests.
        final Provider bouncyCastle = new BouncyCastleProvider();
        final ContentSigner signer =
                new JcaContentSignerBuilder("RSASSA-PSS", pssSpec)
                        .setProvider(bouncyCastle)
                        .build(keyPair.getPrivate());
        final JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE,
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(1, ChronoUnit.DAYS)),
                name, keyPair.getPublic());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
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
