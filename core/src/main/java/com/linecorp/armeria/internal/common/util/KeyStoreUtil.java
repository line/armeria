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
package com.linecorp.armeria.internal.common.util;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.internal.EmptyArrays;

public final class KeyStoreUtil {
    public static KeyPair load(File keyStoreFile,
                               @Nullable String keyStorePassword,
                               @Nullable String keyPassword,
                               @Nullable String alias) throws IOException, GeneralSecurityException {
        try (InputStream in = new FileInputStream(keyStoreFile)) {
            return load(in, keyStorePassword, keyPassword, alias, keyStoreFile);
        }
    }

    public static KeyPair load(InputStream keyStoreStream,
                               @Nullable String keyStorePassword,
                               @Nullable String keyPassword,
                               @Nullable String alias) throws IOException, GeneralSecurityException {
        return load(keyStoreStream, keyStorePassword, keyPassword, alias, null);
    }

    private static KeyPair load(InputStream keyStoreStream,
                                @Nullable String keyStorePassword,
                                @Nullable String keyPassword,
                                @Nullable String alias,
                                @Nullable File keyStoreFile)
            throws IOException, GeneralSecurityException {

        try (InputStream in = new BufferedInputStream(keyStoreStream, 8192)) {
            in.mark(4);
            final String format = detectFormat(in);
            in.reset();

            if (format == null) {
                throw newException("unknown key store format", keyStoreFile,
                                   "(expected: PKCS#12 or JKS format)");
            }

            final KeyStore ks = KeyStore.getInstance(format);
            ks.load(in, keyStorePassword != null ? keyStorePassword.toCharArray() : null);

            // Referred to Vert.x `KeyStoreHelper` for the logic for discovering a key pair:
            // https://github.com/eclipse-vertx/vert.x/blob/52f95ab88f0165c7c0db2ba48e4c06180a8d6655/src/main/java/io/vertx/core/net/impl/KeyStoreHelper.java
            PrivateKey privateKey = null;
            List<X509Certificate> certificateChain = null;
            for (final Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
                final String candidateAlias = e.nextElement();
                if (alias != null && !alias.equals(candidateAlias)) {
                    continue;
                }

                if (ks.isKeyEntry(candidateAlias)) {
                    // Extract the private key.
                    final PrivateKey candidateKey = (PrivateKey) ks.getKey(
                            candidateAlias, keyPassword(keyStorePassword, keyPassword));

                    // Extract the certificate chain.
                    final Certificate[] candidateCertificateChain = ks.getCertificateChain(candidateAlias);
                    if (candidateCertificateChain == null || candidateCertificateChain.length == 0) {
                        throw newException("the key pair contains no certificate chain", keyStoreFile,
                                           "(Specify the alias to choose the right key pair or" +
                                           " ensure the key pair has a certificate chain.");
                    }

                    if (privateKey != null) {
                        throw newException("found more than one key pair from key store", keyStoreFile,
                                           "(Specify the alias to choose the right key pair or" +
                                           " use the key store that has only one key pair.)");
                    }

                    privateKey = candidateKey;
                    certificateChain = Arrays.stream(candidateCertificateChain)
                                             .map(X509Certificate.class::cast)
                                             .collect(ImmutableList.toImmutableList());
                }
            }

            if (privateKey == null) {
                throw newException("no key pair found from key store", keyStoreFile,
                                   "(Use the key store that has at least one key pair.)");
            }

            assert certificateChain != null;

            return new KeyPair(privateKey, certificateChain);
        }
    }

    @Nullable
    private static String detectFormat(InputStream keyStoreStream) throws IOException {
        final byte[] magic = new byte[4];
        try {
            ByteStreams.readFully(keyStoreStream, magic);
        } catch (EOFException unused) {
            // The file is shorter than 4 bytes.
            return null;
        }

        if (magic[0] == 0x30 && magic[1] == (byte) 0x82) {
            return "PKCS12";
        }

        if (magic[0] == (byte) 0xFE && magic[1] == (byte) 0xED &&
            magic[2] == (byte) 0xFE && magic[3] == (byte) 0xED) {
            return "JKS";
        }

        return null;
    }

    @Nullable
    private static char[] keyPassword(@Nullable String keyStorePassword, @Nullable String keyPassword) {
        if (keyPassword != null) {
            return keyPassword.toCharArray();
        }
        if (keyStorePassword != null) {
            return keyStorePassword.toCharArray();
        }
        return null;
    }

    private static IllegalArgumentException newException(String message, @Nullable File keyStoreFile,
                                                         String additionalInfo) {
        if (keyStoreFile != null) {
            return new IllegalArgumentException(
                    message + ": " + keyStoreFile + ' ' + additionalInfo);
        } else {
            return new IllegalArgumentException(message + ' ' + additionalInfo);
        }
    }

    private KeyStoreUtil() {}

    public static final class KeyPair {
        private final PrivateKey privateKey;
        private final List<X509Certificate> certificateChain;

        private KeyPair(PrivateKey privateKey, Iterable<X509Certificate> certificateChain) {
            this.privateKey = privateKey;
            this.certificateChain = ImmutableList.copyOf(certificateChain);
        }

        public PrivateKey privateKey() {
            return privateKey;
        }

        public X509Certificate[] certificateChain() {
            return certificateChain.toArray(EmptyArrays.EMPTY_X509_CERTIFICATES);
        }
    }
}
