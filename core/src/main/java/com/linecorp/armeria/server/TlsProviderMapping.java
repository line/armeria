/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.Consumer;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.SslContextFactory;
import com.linecorp.armeria.internal.common.TlsProviderUtil;
import com.linecorp.armeria.server.ServerTlsSpec.ServerTlsSpecBuilder;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.Mapping;

final class TlsProviderMapping implements Mapping<String, SslContext> {

    private final TlsProvider tlsProvider;
    private final TlsEngineType tlsEngineType;
    @Nullable
    private final ServerTlsConfig tlsConfig;
    private final SslContextFactory sslContextFactory;

    TlsProviderMapping(TlsProvider tlsProvider, TlsEngineType tlsEngineType,
                       @Nullable ServerTlsConfig tlsConfig, SslContextFactory sslContextFactory) {
        this.tlsProvider = tlsProvider;
        this.tlsEngineType = tlsEngineType;
        this.tlsConfig = tlsConfig;
        this.sslContextFactory = sslContextFactory;
    }

    @Override
    public SslContext map(@Nullable String hostname) {
        if (hostname == null) {
            hostname = "*";
        } else {
            hostname = TlsProviderUtil.normalizeHostname(hostname);
        }
        final TlsKeyPair keyPair = tlsProvider.keyPair(hostname);
        final List<X509Certificate> trustedCertificates = tlsProvider.trustedCertificates(hostname);
        final Consumer<SslContextBuilder> tlsCustomizer =
                tlsConfig != null ? tlsConfig.tlsCustomizer() : ignored -> {};
        final ClientAuth clientAuth = tlsConfig != null ? tlsConfig.clientAuth() : ClientAuth.NONE;
        final boolean allowUnsafeCiphers = tlsConfig != null ? tlsConfig.allowsUnsafeCiphers() : false;
        if (keyPair == null) {
            throw new IllegalStateException("No TLS key pair found for " + hostname);
        }
        final ServerTlsSpecBuilder builder = ServerTlsSpec.builder()
                                                          .tlsKeyPair(keyPair)
                                                          .engineType(tlsEngineType)
                                                          .tlsCustomizer(tlsCustomizer)
                                                          .clientAuth(clientAuth);
        if (trustedCertificates != null) {
            builder.trustedCertificates(trustedCertificates);
        }
        return sslContextFactory.getOrCreate(builder.build(), allowUnsafeCiphers);
    }

    void release(SslContext sslContext) {
        sslContextFactory.release(sslContext);
    }
}
