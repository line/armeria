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

import javax.net.ssl.SSLEngine;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.SslContextFactory;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCountUtil;

final class BootstrapSslContexts {

    private final ClientTlsSpec http1OnlyTlsSpec;
    private final ClientTlsSpec defaultTlsSpec;
    private final SslContextFactory sslContextFactory;
    private final SslContext http1OnlySslCtx;
    private final SslContext sslCtx;

    BootstrapSslContexts(ClientTlsSpec baseClientTlsSpec, ClientFactoryOptions options) {
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

        @Nullable
        MeterIdPrefix meterIdPrefix = null;
        if (options.tlsConfig() != ClientTlsConfig.NOOP) {
            meterIdPrefix = options.tlsConfig().meterIdPrefix();
        }
        sslContextFactory = new SslContextFactory(meterIdPrefix, options.meterRegistry());

        SslContext http1OnlySslCtx = null;
        SslContext sslCtx = null;
        boolean success = false;
        try {
            http1OnlySslCtx = sslContextFactory.getOrCreate(http1OnlyTlsSpec);
            sslCtx = sslContextFactory.getOrCreate(defaultTlsSpec);
            // Eagerly validate the HTTP/2 SSL context using a temporary engine so that
            // configuration failures are surfaced fail-fast at ClientFactory build time.
            SSLEngine engine = null;
            try {
                engine = sslCtx.newEngine(ByteBufAllocator.DEFAULT);
            } finally {
                ReferenceCountUtil.release(engine);
            }
            success = true;
        } finally {
            if (!success) {
                if (sslCtx != null) {
                    sslContextFactory.release(sslCtx);
                }
                if (http1OnlySslCtx != null) {
                    sslContextFactory.release(http1OnlySslCtx);
                }
            }
        }
        this.http1OnlySslCtx = http1OnlySslCtx;
        this.sslCtx = sslCtx;
    }

    SslContextFactory sslContextFactory() {
        return sslContextFactory;
    }

    ClientTlsSpec getClientTlsSpec(SessionProtocol sessionProtocol) {
        return sessionProtocol.isExplicitHttp1() ? http1OnlyTlsSpec : defaultTlsSpec;
    }

    SslContext getSslContext(HttpPreference httpPreference) {
        return httpPreference == HttpPreference.HTTP1_REQUIRED ? http1OnlySslCtx : sslCtx;
    }

    void release() {
        sslContextFactory.release(http1OnlySslCtx);
        sslContextFactory.release(sslCtx);
    }
}
