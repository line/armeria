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

package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;

import com.linecorp.armeria.common.AbstractTlsSpecBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * A builder for {@link ServerTlsSpec}.
 */
@UnstableApi
public final class ServerTlsSpecBuilder extends AbstractTlsSpecBuilder<ServerTlsSpecBuilder, ServerTlsSpec> {

    private ClientAuth clientAuth = ClientAuth.NONE;
    private String hostnamePattern = "UNKNOWN";
    private Consumer<? super SslContextBuilder> tlsCustomizer = SslContextUtil.DEFAULT_NOOP_CUSTOMIZER;
    @Nullable
    private KeyManagerFactory keyManagerFactory;

    /**
     * Sets the client authentication requirement.
     */
    public ServerTlsSpecBuilder clientAuth(ClientAuth clientAuth) {
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
        checkArgument(tlsKeyPair() != null ^ keyManagerFactory != null,
                      "Exactly one of 'tlsKeyPair' or 'keyManagerFactory' must be set, but not both");
        return new ServerTlsSpec(SslContextUtil.supportedTlsVersions(engineType().sslProvider()),
                                 SslContextUtil.DEFAULT_ALPN_PROTOCOLS, ciphers(), tlsKeyPair(),
                                 trustedCertificates(), verifierFactories(), engineType(), tlsCustomizer,
                                 clientAuth, allowUnsafeCiphers(), keyManagerFactory, hostnamePattern);
    }
}
