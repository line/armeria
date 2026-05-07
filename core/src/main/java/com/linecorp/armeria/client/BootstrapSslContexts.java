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

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.SslContextFactory;

import io.netty.handler.ssl.SslContext;

final class BootstrapSslContexts {

    private final ClientTlsSpec http1OnlyTlsSpec;
    private final ClientTlsSpec defaultTlsSpec;
    private final SslContext http1OnlySslCtx;
    private final SslContext sslCtx;

    BootstrapSslContexts(ClientTlsSpec baseClientTlsSpec, ClientFactoryOptions options,
                         SslContextFactory sslContextFactory) {
        http1OnlyTlsSpec = baseClientTlsSpec.toBuilder()
                                            .engineType(options.tlsEngineType())
                                            .alpnProtocols(SessionProtocol.H1)
                                            .tlsCustomizer(options.tlsCustomizer())
                                            .build();
        defaultTlsSpec = baseClientTlsSpec.toBuilder()
                                          .engineType(options.tlsEngineType())
                                          .alpnProtocols(SessionProtocol.H2)
                                          .tlsCustomizer(options.tlsCustomizer())
                                          .build();
        http1OnlySslCtx = sslContextFactory.getOrCreate(http1OnlyTlsSpec);
        sslCtx = sslContextFactory.getOrCreate(defaultTlsSpec);
    }

    ClientTlsSpec getClientTlsSpec(SessionProtocol sessionProtocol) {
        return sessionProtocol.isExplicitHttp1() ? http1OnlyTlsSpec : defaultTlsSpec;
    }

    SslContext getSslContext(HttpPreference httpPreference) {
        return httpPreference == HttpPreference.HTTP1_REQUIRED ? http1OnlySslCtx : sslCtx;
    }

    void release(SslContextFactory factory) {
        factory.release(http1OnlySslCtx);
        factory.release(sslCtx);
    }
}
