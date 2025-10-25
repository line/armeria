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

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.SslContextFactory;
import com.linecorp.armeria.internal.common.TlsProviderUtil;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Mapping;

final class TlsProviderMapping implements Mapping<String, SslContext> {

    private final SslContextFactory sslContextFactory;

    TlsProviderMapping(TlsProvider tlsProvider, TlsEngineType tlsEngineType,
                       @Nullable ServerTlsConfig tlsConfig, MeterRegistry meterRegistry) {
        sslContextFactory = new SslContextFactory(tlsProvider, tlsEngineType, tlsConfig, meterRegistry);
    }

    @Override
    public SslContext map(@Nullable String hostname) {
        if (hostname == null) {
            hostname = "*";
        } else {
            hostname = TlsProviderUtil.normalizeHostname(hostname);
        }
        return sslContextFactory.getOrCreate(SslContextFactory.SslContextMode.SERVER, hostname);
    }

    void release(SslContext sslContext) {
        sslContextFactory.release(sslContext);
    }
}
