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

package com.linecorp.armeria.client;

import java.security.cert.X509Certificate;
import java.util.List;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;

final class TlsProviderAdapter implements ClientTlsProvider {

    private final TlsProvider tlsProvider;
    private final @Nullable ClientTlsConfig tlsConfig;
    private final TlsEngineType tlsEngineType;

    TlsProviderAdapter(TlsProvider tlsProvider, @Nullable ClientTlsConfig tlsConfig,
                       TlsEngineType tlsEngineType) {
        this.tlsProvider = tlsProvider;
        this.tlsConfig = tlsConfig;
        this.tlsEngineType = tlsEngineType;
    }

    @Override
    public ClientTlsSpec clientTlsSpec(ClientRequestContext ctx) {
        final SessionProtocol sessionProtocol = ctx.sessionProtocol();
        // The SNI hostname is precomputed on the context by ClientUtil before this is called.
        final String hostname = ctx.sniHostname();
        TlsKeyPair keyPair = null;
        if (hostname != null) {
            keyPair = tlsProvider.keyPair(hostname);
        }
        if (keyPair == null) {
            keyPair = tlsProvider.keyPair("*");
        }
        List<X509Certificate> certs = null;
        if (hostname != null) {
            certs = tlsProvider.trustedCertificates(hostname);
        }
        if (certs == null) {
            certs = tlsProvider.trustedCertificates("*");
        }

        final ClientTlsSpecBuilder builder =
                ClientTlsSpec.builder()
                             .engineType(tlsEngineType)
                             .alpnProtocols(sessionProtocol);
        if (tlsConfig != null) {
            builder.tlsCustomizer(tlsConfig.tlsCustomizer())
                   .allowUnsafeCiphers(tlsConfig.allowsUnsafeCiphers());
        }
        if (keyPair != null) {
            builder.tlsKeyPair(keyPair);
        }
        if (certs != null) {
            builder.trustedCertificates(certs);
        }
        if (tlsConfig != null) {
            if (tlsConfig.tlsNoVerifySet()) {
                builder.verifierFactories(TlsPeerVerifierFactory.noVerify());
            } else if (!tlsConfig.insecureHosts().isEmpty()) {
                builder.verifierFactories(TlsPeerVerifierFactory.insecureHosts(tlsConfig.insecureHosts()));
            }
        }
        return builder.build();
    }
}
