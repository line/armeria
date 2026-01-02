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

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.AbstractTlsSpecBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.SslContextBuilder;

/**
 * Builds a {@link ClientTlsSpec}.
 */
@UnstableApi
public final class ClientTlsSpecBuilder extends AbstractTlsSpecBuilder<ClientTlsSpecBuilder, ClientTlsSpec> {

    private Set<String> alpnProtocols = SslContextUtil.DEFAULT_ALPN_PROTOCOLS;
    private Consumer<? super SslContextBuilder> tlsCustomizer = SslContextUtil.DEFAULT_NOOP_CUSTOMIZER;
    @Nullable
    private KeyManagerFactory keyManagerFactory;

    ClientTlsSpecBuilder() {}

    ClientTlsSpecBuilder(ClientTlsSpec clientTlsSpec) {
        super(clientTlsSpec.ciphers(), clientTlsSpec.tlsKeyPair(), clientTlsSpec.trustedCertificates(),
              clientTlsSpec.verifierFactories(), clientTlsSpec.engineType());
        alpnProtocols = clientTlsSpec.alpnProtocols();
        keyManagerFactory = clientTlsSpec.keyManagerFactory();
        tlsCustomizer = clientTlsSpec.tlsCustomizer();
    }

    ClientTlsSpecBuilder alpnProtocols(SessionProtocol sessionProtocol) {
        if (sessionProtocol.isExplicitHttp1()) {
            alpnProtocols = SslContextUtil.DEFAULT_HTTP1_ALPN_PROTOCOLS;
        } else {
            alpnProtocols = SslContextUtil.DEFAULT_ALPN_PROTOCOLS;
        }
        return this;
    }

    ClientTlsSpecBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
        this.tlsCustomizer = requireNonNull(tlsCustomizer, "tlsCustomizer");
        return this;
    }

    ClientTlsSpecBuilder keyManagerFactory(KeyManagerFactory keyManagerFactory) {
        this.keyManagerFactory = requireNonNull(keyManagerFactory, "keyManagerFactory");
        return this;
    }

    /**
     * Returns a newly created {@link ClientTlsSpec} with the properties set so far.
     */
    @Override
    public ClientTlsSpec build() {
        return new ClientTlsSpec(SslContextUtil.supportedTlsVersions(engineType().sslProvider()),
                                 alpnProtocols, ciphers(), tlsKeyPair(), trustedCertificates(),
                                 verifierFactories(), engineType(), tlsCustomizer, keyManagerFactory);
    }
}
