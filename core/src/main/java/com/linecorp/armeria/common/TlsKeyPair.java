/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.common.util.CertificateUtil.toPrivateKey;
import static com.linecorp.armeria.internal.common.util.CertificateUtil.toX509Certificates;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;

/**
 * A pair of a {@link PrivateKey} and a {@link X509Certificate} chain.
 */
@UnstableApi
public final class TlsKeyPair {

    private static final Logger logger = LoggerFactory.getLogger(TlsKeyPair.class);

    // A fixed payload signed with the private key and verified with the leaf certificate's public key to
    // confirm the two form a matching key pair.
    private static final byte[] VALIDATION_PROBE =
            "Armeria TlsKeyPair validation probe".getBytes(StandardCharsets.UTF_8);

    /**
     * Creates a new {@link TlsKeyPair} from the specified key {@link InputStream}, and certificate chain
     * {@link InputStream}.
     */
    public static TlsKeyPair of(InputStream keyInputStream, InputStream certificateChainInputStream) {
        return of(keyInputStream, null, certificateChainInputStream);
    }

    /**
     * Creates a new {@link TlsKeyPair} from the specified key {@link InputStream}, key password
     * {@link InputStream} and certificate chain {@link InputStream}.
     */
    public static TlsKeyPair of(InputStream keyInputStream, @Nullable String keyPassword,
                                InputStream certificateChainInputStream) {
        requireNonNull(keyInputStream, "keyInputStream");
        requireNonNull(certificateChainInputStream, "certificateChainInputStream");
        try {
            final List<X509Certificate> certs = toX509Certificates(certificateChainInputStream);
            final PrivateKey key = toPrivateKey(keyInputStream, keyPassword);
            return of(key, certs);
        } catch (CertificateException | KeyException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Creates a new {@link TlsKeyPair} from the specified key file and certificate chain file.
     */
    public static TlsKeyPair of(File keyFile, File certificateChainFile) {
        return of(keyFile, null, certificateChainFile);
    }

    /**
     * Creates a new {@link TlsKeyPair} from the specified key file, key password and certificate chain
     * file.
     */
    public static TlsKeyPair of(File keyFile, @Nullable String keyPassword, File certificateChainFile) {
        requireNonNull(keyFile, "keyFile");
        requireNonNull(certificateChainFile, "certificateChainFile");
        try {
            final List<X509Certificate> certs = toX509Certificates(certificateChainFile);
            final PrivateKey key = toPrivateKey(keyFile, keyPassword);
            return of(key, certs);
        } catch (CertificateException | KeyException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Creates a new {@link TlsKeyPair} from the specified {@link PrivateKey} and {@link X509Certificate}s.
     */
    public static TlsKeyPair of(PrivateKey key, X509Certificate... certificateChain) {
        requireNonNull(certificateChain, "certificateChain");
        return of(key, ImmutableList.copyOf(certificateChain));
    }

    /**
     * Creates a new {@link TlsKeyPair} from the specified {@link PrivateKey} and {@link X509Certificate}s.
     */
    public static TlsKeyPair of(PrivateKey key, Iterable<? extends X509Certificate> certificateChain) {
        requireNonNull(key, "key");
        requireNonNull(certificateChain, "certificateChain");
        return new TlsKeyPair(key, ImmutableList.copyOf(certificateChain));
    }

    /**
     * Generates a self-signed certificate for the specified {@code hostname}.
     */
    public static TlsKeyPair ofSelfSigned(String hostname) {
        requireNonNull(hostname, "hostname");
        try {
            final SelfSignedCertificate ssc = new SelfSignedCertificate(hostname);
            return of(ssc.key(), ssc.cert());
        } catch (CertificateException e) {
            throw new IllegalStateException("Failed to create a self-signed certificate for " + hostname, e);
        }
    }

    /**
     * Generates a self-signed certificate for the local hostname.
     */
    public static TlsKeyPair ofSelfSigned() {
        return ofSelfSigned(SystemInfo.hostname());
    }

    private final PrivateKey privateKey;
    private final List<X509Certificate> certificateChain;

    private TlsKeyPair(PrivateKey privateKey, List<X509Certificate> certificateChain) {
        checkArgument(!certificateChain.isEmpty(), "certificateChain is empty");
        validateKeyPair(privateKey, certificateChain.get(0));
        this.privateKey = privateKey;
        this.certificateChain = certificateChain;
    }

    /**
     * Ensures that the {@code privateKey} matches the public key of the leaf {@code certificate}, so that a
     * mismatch is reported eagerly here.
     */
    private static void validateKeyPair(PrivateKey privateKey, X509Certificate certificate) {
        final PublicKey publicKey = certificate.getPublicKey();

        final String privateKeyAlgorithm = privateKey.getAlgorithm();
        final String publicKeyAlgorithm = publicKey.getAlgorithm();
        if (privateKeyAlgorithm == null || publicKeyAlgorithm == null) {
            // A provider that does not expose the key algorithm; skip validation rather than fail so that
            // a key we simply cannot introspect is not rejected.
            logger.debug("Skipping key pair validation because a key algorithm is unavailable " +
                         "(privateKey={}, publicKey={}).", privateKeyAlgorithm, publicKeyAlgorithm);
            return;
        }

        // A private and public key of different algorithm families can never form a valid key pair.
        final String privateKeyFamily = keyFamily(privateKeyAlgorithm);
        final String publicKeyFamily = keyFamily(publicKeyAlgorithm);
        checkArgument(keyFamiliesMatch(privateKeyFamily, publicKeyFamily),
                      "The private key algorithm (%s) does not match the leaf certificate's public key " +
                      "algorithm (%s).", privateKeyAlgorithm, publicKeyAlgorithm);

        final String signatureAlgorithm = signatureAlgorithm(privateKeyAlgorithm);
        if (signatureAlgorithm == null) {
            logger.debug("Skipping key pair validation for an unsupported key algorithm: {}",
                         privateKeyAlgorithm);
            return;
        }

        final boolean matches;
        try {
            final Signature signature = Signature.getInstance(signatureAlgorithm);
            signature.initSign(privateKey);
            signature.update(VALIDATION_PROBE);
            final byte[] signed = signature.sign();
            signature.initVerify(publicKey);
            signature.update(VALIDATION_PROBE);
            matches = signature.verify(signed);
        } catch (GeneralSecurityException e) {
            logger.debug("Skipping key pair validation because the probe signature could not be computed " +
                         "with {}.", signatureAlgorithm, e);
            return;
        }

        checkArgument(matches, "The private key does not match the public key of the leaf certificate. " +
                               "Make sure the private key and the certificate belong to the same key pair.");
    }

    /**
     * Returns the algorithm family of the specified key algorithm, used to compare a private key against a
     * public key. Aliases that denote the same family (e.g. {@code "EC"} and {@code "ECDSA"}, or
     * {@code "RSA"} and {@code "RSASSA-PSS"}) are normalized to a single name. The EdDSA curve names
     * ({@code "Ed25519"}, {@code "Ed448"}) and the generic {@code "EdDSA"} are intentionally left distinct
     * so that {@link #keyFamiliesMatch(String, String)} can accept a generic key against a specific curve
     * while still rejecting two different curves.
     */
    private static String keyFamily(String keyAlgorithm) {
        final String upperCased = Ascii.toUpperCase(keyAlgorithm);
        switch (upperCased) {
            case "ECDSA":
                return "EC";
            case "RSASSA-PSS":
                return "RSA";
            default:
                return upperCased;
        }
    }

    /**
     * Returns whether the two key algorithm families can form a key pair. Identical families always match.
     * The generic EdDSA family ({@code "EDDSA"}) is treated as compatible with a specific curve
     * ({@code "ED25519"} or {@code "ED448"}), because different providers may report the same EdDSA key
     * either by its generic name or by its curve name. Two different specific curves never match.
     */
    private static boolean keyFamiliesMatch(String privateKeyFamily, String publicKeyFamily) {
        if (privateKeyFamily.equals(publicKeyFamily)) {
            return true;
        }
        return ("EDDSA".equals(privateKeyFamily) && isEdDsaCurve(publicKeyFamily)) ||
               ("EDDSA".equals(publicKeyFamily) && isEdDsaCurve(privateKeyFamily));
    }

    private static boolean isEdDsaCurve(String keyFamily) {
        return "ED25519".equals(keyFamily) || "ED448".equals(keyFamily);
    }

    /**
     * Returns the signature algorithm to probe the specified key algorithm with, or {@code null} if the
     * algorithm is not recognized.
     */
    @Nullable
    private static String signatureAlgorithm(String keyAlgorithm) {
        switch (Ascii.toUpperCase(keyAlgorithm)) {
            case "RSA":
                return "SHA256withRSA";
            case "EC":
            case "ECDSA":
                return "SHA256withECDSA";
            case "DSA":
                return "SHA256withDSA";
            case "ED25519":
                return "Ed25519";
            case "ED448":
                return "Ed448";
            case "EDDSA":
                return "EdDSA";
            default:
                return null;
        }
    }

    /**
     * Returns the private key.
     */
    public PrivateKey privateKey() {
        return privateKey;
    }

    /**
     * Returns the certificate chain.
     */
    public List<X509Certificate> certificateChain() {
        return certificateChain;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof TlsKeyPair)) {
            return false;
        }

        final TlsKeyPair that = (TlsKeyPair) o;
        return privateKey.equals(that.privateKey) && certificateChain.equals(that.certificateChain);
    }

    @Override
    public int hashCode() {
        return privateKey.hashCode() * 31 + certificateChain.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("privateKey", "****")
                          .add("certificateChain", certificateChain)
                          .toString();
    }
}
