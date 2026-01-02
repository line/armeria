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

package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.AbstractTlsSpec;
import com.linecorp.armeria.common.AbstractTlsSpecBuilder;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * Specifies TLS configuration for server connections.
 */
@UnstableApi
public final class ServerTlsSpec extends AbstractTlsSpec {

    private final ClientAuth clientAuth;
    private final String hostnamePattern;

    ServerTlsSpec(Set<String> protocols, Set<String> alpnProtocols,
                  Set<String> ciphers, @Nullable TlsKeyPair tlsKeyPair,
                  List<X509Certificate> trustedCertificates,
                  List<TlsPeerVerifierFactory> verifierFactories, TlsEngineType engineType,
                  Consumer<? super SslContextBuilder> tlsCustomizer, ClientAuth clientAuth,
                  @Nullable KeyManagerFactory keyManagerFactory, String hostnamePattern) {
        super(protocols, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates, verifierFactories, engineType,
              tlsCustomizer, keyManagerFactory);
        this.clientAuth = clientAuth;
        this.hostnamePattern = hostnamePattern;
    }

    @Override
    public boolean isServer() {
        return true;
    }

    /**
     * Returns the client authentication requirement.
     */
    public String clientAuth() {
        return clientAuth.name();
    }

    /**
     * Returns the hostname pattern for this TLS configuration.
     * @deprecated will be removed
     */
    @Deprecated
    public String hostnamePattern() {
        return hostnamePattern;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!super.equals(o)) {
            return false;
        }
        if (!(o instanceof ServerTlsSpec)) {
            return false;
        }
        final ServerTlsSpec that = (ServerTlsSpec) o;
        return clientAuth == that.clientAuth &&
               hostnamePattern.equals(that.hostnamePattern);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), clientAuth, hostnamePattern);
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
                          .add("clientAuth", clientAuth)
                          .add("hostnamePattern", hostnamePattern)
                          .toString();
    }

    static ServerTlsSpecBuilder builder() {
        return new ServerTlsSpecBuilder();
    }

    static class ServerTlsSpecBuilder extends AbstractTlsSpecBuilder<ServerTlsSpecBuilder, ServerTlsSpec> {

        private ClientAuth clientAuth = ClientAuth.NONE;
        private String hostnamePattern = "UNKNOWN";
        private Consumer<? super SslContextBuilder> tlsCustomizer = SslContextUtil.DEFAULT_NOOP_CUSTOMIZER;
        @Nullable
        private KeyManagerFactory keyManagerFactory;

        ServerTlsSpecBuilder clientAuth(ClientAuth clientAuth) {
            this.clientAuth = requireNonNull(clientAuth, "clientAuth");
            return this;
        }

        ServerTlsSpecBuilder hostnamePattern(String hostnamePattern) {
            this.hostnamePattern = requireNonNull(hostnamePattern, "hostnamePattern");
            return this;
        }

        ServerTlsSpecBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
            this.tlsCustomizer = requireNonNull(tlsCustomizer, "tlsCustomizer");
            return this;
        }

        ServerTlsSpecBuilder keyManagerFactory(KeyManagerFactory keyManagerFactory) {
            this.keyManagerFactory = requireNonNull(keyManagerFactory, "keyManagerFactory");
            return this;
        }

        @Override
        public ServerTlsSpec build() {
            checkArgument(tlsKeyPair() != null || keyManagerFactory != null,
                          "Either 'tlsKeyPair' or 'keyManagerFactory' must be set");
            return new ServerTlsSpec(SslContextUtil.supportedTlsVersions(engineType().sslProvider()),
                                     SslContextUtil.DEFAULT_ALPN_PROTOCOLS, ciphers(), tlsKeyPair(),
                                     trustedCertificates(), verifierFactories(), engineType(), tlsCustomizer,
                                     clientAuth, keyManagerFactory, hostnamePattern);
        }
    }
}
