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

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AbstractTlsSpec;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TlsEngineType;

import io.netty.handler.ssl.SslContextBuilder;

/**
 * Specifies TLS configuration for client connections.
 */
@UnstableApi
public final class ClientTlsSpec extends AbstractTlsSpec {

    private static final ClientTlsSpec DEFAULT = builder().build();

    /**
     * Returns the default {@link ClientTlsSpec}.
     */
    public static ClientTlsSpec of() {
        return DEFAULT;
    }

    /**
     * Returns a new {@link ClientTlsSpecBuilder}.
     */
    public static ClientTlsSpecBuilder builder() {
        return new ClientTlsSpecBuilder();
    }

    private final Set<String> overrideAlpnProtocols;
    private final String endpointIdentificationAlgorithm;

    ClientTlsSpec(Set<String> tlsVersions, Set<String> alpnProtocols, Set<String> ciphers,
                  @Nullable TlsKeyPair tlsKeyPair,
                  List<X509Certificate> trustedCertificates,
                  List<TlsPeerVerifierFactory> verifierFactories, TlsEngineType engineType,
                  Consumer<? super SslContextBuilder> tlsCustomizer,
                  @Nullable KeyManagerFactory keyManagerFactory,
                  Set<String> overrideAlpnProtocols, String endpointIdentificationAlgorithm) {
        super(tlsVersions, alpnProtocols, ciphers, tlsKeyPair,
              trustedCertificates, verifierFactories, engineType, tlsCustomizer, keyManagerFactory);
        this.overrideAlpnProtocols = ImmutableSet.copyOf(overrideAlpnProtocols);
        this.endpointIdentificationAlgorithm = endpointIdentificationAlgorithm;
    }

    @Override
    public Set<String> alpnProtocols() {
        if (!overrideAlpnProtocols.isEmpty()) {
            return overrideAlpnProtocols;
        }
        return super.alpnProtocols();
    }

    Set<String> baseAlpnProtocols() {
        return super.alpnProtocols();
    }

    Set<String> overrideAlpnProtocols() {
        return overrideAlpnProtocols;
    }

    /**
     * Returns the endpoint identification algorithm used for JSSE hostname verification.
     * An empty string {@code ""} disables JSSE hostname verification.
     */
    public String endpointIdentificationAlgorithm() {
        return endpointIdentificationAlgorithm;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final ClientTlsSpec that = (ClientTlsSpec) o;
        return Objects.equal(overrideAlpnProtocols, that.overrideAlpnProtocols) &&
               Objects.equal(endpointIdentificationAlgorithm, that.endpointIdentificationAlgorithm);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), overrideAlpnProtocols, endpointIdentificationAlgorithm);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("tlsVersions", tlsVersions())
                          .add("alpnProtocols", alpnProtocols())
                          .add("ciphers", ciphers())
                          .add("tlsKeyPair", tlsKeyPair())
                          .add("trustedCertificates", trustedCertificates())
                          .add("verifierFactories", verifierFactories())
                          .add("engineType", engineType())
                          .add("tlsCustomizer", tlsCustomizer())
                          .add("keyManagerFactory", keyManagerFactory())
                          .add("endpointIdentificationAlgorithm", endpointIdentificationAlgorithm())
                          .toString();
    }

    /**
     * Returns a new {@link ClientTlsSpecBuilder} initialized with the properties of this
     * {@link ClientTlsSpec}.
     */
    public ClientTlsSpecBuilder toBuilder() {
        return new ClientTlsSpecBuilder(this);
    }

    @Override
    public boolean isServer() {
        return false;
    }
}
