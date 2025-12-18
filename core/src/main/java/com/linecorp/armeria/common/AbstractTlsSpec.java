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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.SslContextBuilder;

/**
 * Base class for TLS configuration specifications.
 */
@UnstableApi
public abstract class AbstractTlsSpec {

    private final Set<String> tlsVersions;
    private final Set<String> alpnProtocols;
    private final Set<String> ciphers;
    @Nullable
    private final TlsKeyPair tlsKeyPair;
    private final List<X509Certificate> trustedCertificates;
    private final List<TlsPeerVerifierFactory> verifierFactories;
    private final TlsEngineType engineType;
    private final Consumer<? super SslContextBuilder> tlsCustomizer;
    @Nullable
    private final KeyManagerFactory keyManagerFactory;

    /**
     * Creates a new instance with the specified TLS configuration.
     */
    protected AbstractTlsSpec(Set<String> tlsVersions, Set<String> alpnProtocols, Set<String> ciphers,
                              @Nullable TlsKeyPair tlsKeyPair, List<X509Certificate> trustedCertificates,
                              List<TlsPeerVerifierFactory> verifierFactories, TlsEngineType engineType,
                              Consumer<? super SslContextBuilder> tlsCustomizer,
                              @Nullable KeyManagerFactory keyManagerFactory) {
        checkArgument(tlsKeyPair == null || keyManagerFactory == null,
                      "'tlsKeyPair' and 'keyManagerFactory' cannot both be set");
        SslContextUtil.checkVersionsSupported(tlsVersions, engineType.sslProvider());
        this.tlsVersions = ImmutableSet.copyOf(tlsVersions);
        this.alpnProtocols = ImmutableSet.copyOf(alpnProtocols);
        this.ciphers = ImmutableSet.copyOf(ciphers);
        this.tlsKeyPair = tlsKeyPair;
        this.trustedCertificates = ImmutableList.copyOf(trustedCertificates);
        this.verifierFactories = ImmutableList.copyOf(verifierFactories);
        this.engineType = engineType;
        this.tlsCustomizer = tlsCustomizer;
        this.keyManagerFactory = keyManagerFactory;
    }

    /**
     * Returns the supported TLS protocols.
     */
    public final Set<String> tlsVersions() {
        return tlsVersions;
    }

    /**
     * Returns the supported ALPN protocols.
     */
    public final Set<String> alpnProtocols() {
        return alpnProtocols;
    }

    /**
     * Returns the supported cipher suites.
     */
    public final Set<String> ciphers() {
        return ciphers;
    }

    /**
     * Returns the TLS key pair, or {@code null} if not configured.
     */
    public final @Nullable TlsKeyPair tlsKeyPair() {
        return tlsKeyPair;
    }

    /**
     * Returns the trusted certificates for peer verification.
     */
    public final List<X509Certificate> trustedCertificates() {
        return trustedCertificates;
    }

    /**
     * Returns the TLS peer verifier factories.
     */
    public final List<TlsPeerVerifierFactory> verifierFactories() {
        return verifierFactories;
    }

    /**
     * Returns the TLS engine type.
     */
    public final TlsEngineType engineType() {
        return engineType;
    }

    /**
     * Returns the TLS customizer function used to create this tls specification.
     * This method is introduced for backwards compatibility and will be removed.
     * @deprecated will be removed
     */
    @Deprecated
    public final Consumer<? super SslContextBuilder> tlsCustomizer() {
        return tlsCustomizer;
    }

    /**
     * Returns the key manager factory, or {@code null} if not configured.
     * This method is introduced for backwards compatibility and will be removed.
     * @deprecated will be removed
     */
    @Deprecated
    public final @Nullable KeyManagerFactory keyManagerFactory() {
        return keyManagerFactory;
    }

    /**
     * Returns {@code true} if this is a server-side TLS specification, {@code false} otherwise.
     */
    public abstract boolean isServer();

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (this == o) {
            return true;
        }
        final AbstractTlsSpec tlsSpec = (AbstractTlsSpec) o;
        return Objects.equal(tlsVersions, tlsSpec.tlsVersions()) &&
               Objects.equal(alpnProtocols, tlsSpec.alpnProtocols()) &&
               Objects.equal(ciphers, tlsSpec.ciphers()) &&
               Objects.equal(tlsKeyPair, tlsSpec.tlsKeyPair()) &&
               Objects.equal(trustedCertificates, tlsSpec.trustedCertificates()) &&
               Objects.equal(verifierFactories, tlsSpec.verifierFactories()) &&
               engineType == tlsSpec.engineType() &&
               Objects.equal(tlsCustomizer, tlsSpec.tlsCustomizer()) &&
               Objects.equal(keyManagerFactory, tlsSpec.keyManagerFactory());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(tlsVersions, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates,
                                verifierFactories, engineType, tlsCustomizer, keyManagerFactory);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("tlsVersions", tlsVersions)
                          .add("alpnProtocols", alpnProtocols)
                          .add("ciphers", ciphers)
                          .add("tlsKeyPair", tlsKeyPair)
                          .add("trustedCertificates", trustedCertificates)
                          .add("verifierFactories", verifierFactories)
                          .add("engineType", engineType)
                          .add("tlsCustomizer", tlsCustomizer)
                          .add("keyManagerFactory", keyManagerFactory)
                          .toString();
    }
}
