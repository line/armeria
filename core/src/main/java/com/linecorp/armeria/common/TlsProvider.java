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

import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Server;

/**
 * Provides {@link TlsKeyPair}s for TLS handshakes.
 */
@UnstableApi
@FunctionalInterface
public interface TlsProvider {

    /**
     * Returns a {@link TlsProvider} for a {@link Server} which always returns the specified {@link TlsKeyPair}.
     */
    static TlsProvider of(TlsKeyPair tlsKeyPair) {
        requireNonNull(tlsKeyPair, "tlsKeyPair");
        return builder().keyPair(tlsKeyPair).build();
    }

    /**
     * Returns a newly created {@link TlsProviderBuilder}.
     *
     * <p>Example usage:
     * <pre>{@code
     * TlsProvider
     *   .builder()
     *   // Set the default key pair.
     *   .keyPair(TlsKeyPair.of(...))
     *   // Set the key pair for "api.example.com".
     *   .keyPair("api.example.com", TlsKeyPair.of(...))
     *   // Set the key pair for "web.example.com".
     *   .keyPair("web.example.com", TlsKeyPair.of(...))
     *   .build();
     * }</pre>
     */
    static TlsProviderBuilder builder() {
        return new TlsProviderBuilder();
    }

    /**
     * Finds a {@link TlsKeyPair} for the specified {@code hostname}.
     *
     * <p>If no matching {@link TlsKeyPair} is found for a hostname, {@code "*"} will be specified to get the
     * default {@link TlsKeyPair}.
     * If no default {@link TlsKeyPair} is found, {@code null} will be returned.
     */
    @Nullable
    TlsKeyPair keyPair(String hostname);

    /**
     * Returns trusted certificates for verifying the remote endpoint's certificate.
     *
     * <p>If no matching {@link X509Certificate}s are found for a hostname, {@code "*"} will be specified to get
     * the default {@link X509Certificate}s.
     * The system default will be used if this method returns null.
     */
    @Nullable
    default List<X509Certificate> trustedCertificates(String hostname) {
        return null;
    }
}
