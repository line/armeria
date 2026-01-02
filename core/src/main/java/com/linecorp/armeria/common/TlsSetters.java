/*
 * Copyright 2022 LINE Corporation
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

import java.io.File;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.handler.ssl.SslContextBuilder;

/**
 * Sets properties for TLS or SSL.
 */
@UnstableApi
public interface TlsSetters {

    /**
     * Configures SSL or TLS with the specified {@code keyCertChainFile}
     * and cleartext {@code keyFile}.
     *
     * @deprecated Use {@link #tls(TlsKeyPair)} instead.
     */
    @Deprecated
    default TlsSetters tls(File keyCertChainFile, File keyFile) {
        return tls(TlsKeyPair.of(keyFile, keyCertChainFile));
    }

    /**
     * Configures SSL or TLS with the specified {@code keyCertChainFile},
     * {@code keyFile} and {@code keyPassword}.
     *
     * @deprecated Use {@link #tls(TlsKeyPair)} instead.
     */
    @Deprecated
    default TlsSetters tls(File keyCertChainFile, File keyFile, @Nullable String keyPassword) {
        return tls(TlsKeyPair.of(keyFile, keyPassword, keyCertChainFile));
    }

    /**
     * Configures SSL or TLS  with the specified {@code keyCertChainInputStream} and
     * cleartext {@code keyInputStream}.
     *
     * @deprecated Use {@link #tls(TlsKeyPair)} instead.
     */
    @Deprecated
    default TlsSetters tls(InputStream keyCertChainInputStream, InputStream keyInputStream) {
        return tls(TlsKeyPair.of(keyInputStream, null, keyCertChainInputStream));
    }

    /**
     * Configures SSL or TLS of this with the specified {@code keyCertChainInputStream},
     * {@code keyInputStream} and {@code keyPassword}.
     *
     * @deprecated Use {@link #tls(TlsKeyPair)} instead.
     */
    @Deprecated
    default TlsSetters tls(InputStream keyCertChainInputStream, InputStream keyInputStream,
                           @Nullable String keyPassword) {
        return tls(TlsKeyPair.of(keyInputStream, keyPassword, keyCertChainInputStream));
    }

    /**
     * Configures SSL or TLS with the specified cleartext {@link PrivateKey} and
     * {@link X509Certificate} chain.
     *
     * @deprecated Use {@link #tls(TlsKeyPair)} instead.
     */
    @Deprecated
    default TlsSetters tls(PrivateKey key, X509Certificate... keyCertChain) {
        return tls(TlsKeyPair.of(key, keyCertChain));
    }

    /**
     * Configures SSL or TLS with the specified cleartext {@link PrivateKey} and
     * {@link X509Certificate} chain.
     *
     * @deprecated Use {@link #tls(TlsKeyPair)} instead.
     */
    @Deprecated
    default TlsSetters tls(PrivateKey key, Iterable<? extends X509Certificate> keyCertChain) {
        return tls(TlsKeyPair.of(key, keyCertChain));
    }

    /**
     * Configures SSL or TLS with the specified {@link PrivateKey}, {@code keyPassword} and
     * {@link X509Certificate} chain.
     *
     * @deprecated Use {@link #tls(TlsKeyPair)} instead.
     */
    @Deprecated
    default TlsSetters tls(PrivateKey key, @Nullable String keyPassword, X509Certificate... keyCertChain) {
        // keyPassword is not required for PrivateKey since it is not encrypted.
        return tls(TlsKeyPair.of(key, keyCertChain));
    }

    /**
     * Configures SSL or TLS with the specified {@link PrivateKey}, {@code keyPassword} and
     * {@link X509Certificate} chain.
     *
     * @deprecated Use {@link #tls(TlsKeyPair)} instead.
     */
    @Deprecated
    default TlsSetters tls(PrivateKey key, @Nullable String keyPassword,
                           Iterable<? extends X509Certificate> keyCertChain) {
        // keyPassword is not required for PrivateKey since it is not encrypted.
        return tls(TlsKeyPair.of(key, keyCertChain));
    }

    /**
     * Configures SSL or TLS with the specified {@link TlsKeyPair}.
     */
    @UnstableApi
    TlsSetters tls(TlsKeyPair tlsKeyPair);

    /**
     * Configures SSL or TLS with the specified {@link KeyManagerFactory}.
     */
    TlsSetters tls(KeyManagerFactory keyManagerFactory);

    /**
     * Adds the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     */
    TlsSetters tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer);
}
