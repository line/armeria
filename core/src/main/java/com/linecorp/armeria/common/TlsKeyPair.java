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

import static com.linecorp.armeria.internal.common.util.CertificateUtil.toPrivateKey;
import static com.linecorp.armeria.internal.common.util.CertificateUtil.toX509Certificates;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.InputStream;
import java.security.KeyException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

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
        this.privateKey = privateKey;
        this.certificateChain = certificateChain;
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
