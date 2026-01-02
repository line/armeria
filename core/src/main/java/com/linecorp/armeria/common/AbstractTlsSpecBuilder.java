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
import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

/**
 * Base builder for TLS configuration specifications.
 */
@UnstableApi
public abstract class AbstractTlsSpecBuilder<SELF extends AbstractTlsSpecBuilder<SELF, T>, T> {

    private Set<String> ciphers = SslContextUtil.DEFAULT_CIPHERS;
    @Nullable
    private TlsKeyPair tlsKeyPair;
    private List<X509Certificate> trustedCertificates = ImmutableList.of();
    private List<TlsPeerVerifierFactory> verifierFactories = ImmutableList.of();
    private TlsEngineType engineType = Flags.tlsEngineType();

    /**
     * Creates a new builder with default settings.
     */
    protected AbstractTlsSpecBuilder() {}

    /**
     * Creates a new builder initialized with the specified configuration.
     */
    protected AbstractTlsSpecBuilder(Set<String> ciphers, @Nullable TlsKeyPair tlsKeyPair,
                                     List<X509Certificate> trustedCertificates,
                                     List<TlsPeerVerifierFactory> verifierFactories, TlsEngineType engineType) {
        this.ciphers = ciphers;
        this.tlsKeyPair = tlsKeyPair;
        this.trustedCertificates = trustedCertificates;
        this.verifierFactories = verifierFactories;
        this.engineType = engineType;
    }

    /**
     * Sets the cipher suites to use.
     */
    public final SELF ciphers(String... ciphers) {
        requireNonNull(ciphers, "ciphers");
        return ciphers(ImmutableSet.copyOf(ciphers));
    }

    /**
     * Sets the cipher suites to use.
     */
    public final SELF ciphers(Iterable<String> ciphers) {
        requireNonNull(ciphers, "ciphers");
        final Set<String> ciphersSet = ImmutableSet.copyOf(ciphers);
        checkArgument(!ciphersSet.isEmpty(), "At least one cipher must be specified.");
        this.ciphers = ciphersSet;
        return self();
    }

    /**
     * Returns the configured cipher suites.
     */
    protected final Set<String> ciphers() {
        return ciphers;
    }

    /**
     * Sets the TLS key pair to use for client authentication.
     */
    public final SELF tlsKeyPair(TlsKeyPair tlsKeyPair) {
        this.tlsKeyPair = requireNonNull(tlsKeyPair, "tlsKeyPair");
        return self();
    }

    /**
     * Returns the configured TLS key pair.
     */
    protected final @Nullable TlsKeyPair tlsKeyPair() {
        return tlsKeyPair;
    }

    /**
     * Sets the trusted certificates to use for peer verification.
     */
    public final SELF trustedCertificates(X509Certificate... trustedCertificates) {
        requireNonNull(trustedCertificates, "trustedCertificates");
        return trustedCertificates(ImmutableList.copyOf(trustedCertificates));
    }

    /**
     * Sets the trusted certificates to use for peer verification.
     */
    public final SELF trustedCertificates(Iterable<X509Certificate> trustedCertificates) {
        requireNonNull(trustedCertificates, "trustedCertificates");
        this.trustedCertificates = ImmutableList.copyOf(trustedCertificates);
        return self();
    }

    /**
     * Returns the configured trusted certificates.
     */
    protected final List<X509Certificate> trustedCertificates() {
        return trustedCertificates;
    }

    /**
     * Sets the TLS peer verifier factories that will be used to verify the peer.
     * Note that {@link TlsPeerVerifierFactory#create(TlsPeerVerifier)} will be applied in the
     * reverse order.
     * @see TlsPeerVerifierFactory
     */
    public final SELF verifierFactories(TlsPeerVerifierFactory... verifierFactories) {
        requireNonNull(verifierFactories, "verifierFactories");
        return verifierFactories(ImmutableList.copyOf(verifierFactories));
    }

    /**
     * Sets the TLS peer verifier factories that will be used to verify the peer.
     * Note that {@link TlsPeerVerifierFactory#create(TlsPeerVerifier)} will be applied in the
     * reverse order.
     * @see TlsPeerVerifierFactory
     */
    public final SELF verifierFactories(Iterable<TlsPeerVerifierFactory> verifierFactories) {
        requireNonNull(verifierFactories, "verifierFactories");
        this.verifierFactories = ImmutableList.copyOf(verifierFactories);
        return self();
    }

    /**
     * Returns the configured TLS peer verifier factories.
     */
    protected final List<TlsPeerVerifierFactory> verifierFactories() {
        return verifierFactories;
    }

    /**
     * Sets the TLS engine type to use.
     */
    public final SELF engineType(TlsEngineType engineType) {
        this.engineType = requireNonNull(engineType, "engineType");
        return self();
    }

    /**
     * Returns the configured TLS engine type.
     */
    protected final TlsEngineType engineType() {
        return engineType;
    }

    @SuppressWarnings("unchecked")
    final SELF self() {
        return (SELF) this;
    }

    /**
     * Returns a newly created TLS specification with the properties set so far.
     */
    public abstract T build();
}
