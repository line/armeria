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

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.TlsProviderUtil;

/**
 * Adapts a {@link TlsProvider} into a {@link ServerTlsProvider} by resolving
 * {@link ServerTlsSpec} from hostname-based {@link TlsKeyPair} lookup combined
 * with {@link ServerTlsConfig}.
 */
final class TlsProviderAdapter implements ServerTlsProvider, AutoCloseable {

    private final TlsProvider delegate;
    private final ServerTlsConfig tlsConfig;

    TlsProviderAdapter(TlsProvider delegate, ServerTlsConfig tlsConfig) {
        this.delegate = delegate;
        this.tlsConfig = tlsConfig;
    }

    @Override
    public CompletableFuture<@Nullable ServerTlsSpec> serverTlsSpec(ConnectionContext ctx) {
        String hostname = ctx.sniHostname();
        if (hostname == null) {
            hostname = "*";
        } else {
            hostname = TlsProviderUtil.normalizeHostname(hostname);
        }
        final TlsKeyPair keyPair = delegate.keyPair(hostname);
        if (keyPair == null) {
            return UnmodifiableFuture.completedFuture(null);
        }
        final List<X509Certificate> trustedCertificates = delegate.trustedCertificates(hostname);
        final ServerTlsSpecBuilder builder = ServerTlsSpec.builder()
                                                          .tlsKeyPair(keyPair)
                                                          .engineType(tlsConfig.tlsEngineType())
                                                          .tlsCustomizer(tlsConfig.tlsCustomizer())
                                                          .clientAuth(tlsConfig.clientAuth())
                                                          .allowUnsafeCiphers(tlsConfig.allowsUnsafeCiphers());
        if (trustedCertificates != null) {
            builder.trustedCertificates(trustedCertificates);
        }
        return UnmodifiableFuture.completedFuture(builder.build());
    }

    @Override
    public void close() throws Exception {
        if (delegate.autoClose()) {
            delegate.close();
        }
    }
}
