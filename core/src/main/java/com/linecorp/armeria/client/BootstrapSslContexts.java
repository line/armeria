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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.SslContextFactory;

import io.netty.handler.ssl.SslContext;

final class BootstrapSslContexts {

    private final Map<SessionProtocol, ClientTlsSpec> tlsSpecs;
    private final Map<SessionProtocol, SslContext> contexts;

    BootstrapSslContexts(ClientTlsSpec baseClientTlsSpec, ClientFactoryOptions options,
                         SslContextFactory sslContextFactory) {
        final ImmutableMap.Builder<SessionProtocol, ClientTlsSpec> tlsSpecsBuilder = ImmutableMap.builder();
        final ImmutableMap.Builder<SessionProtocol, SslContext> sslContextsBuilder = ImmutableMap.builder();
        for (SessionProtocol sessionProtocol: SessionProtocol.httpsValues()) {
            final ClientTlsSpec tlsSpec = baseClientTlsSpec.toBuilder()
                                                           .engineType(options.tlsEngineType())
                                                           .alpnProtocols(sessionProtocol)
                                                           .tlsCustomizer(options.tlsCustomizer())
                                                           .build();
            tlsSpecsBuilder.put(sessionProtocol, tlsSpec);
            sslContextsBuilder.put(sessionProtocol, sslContextFactory.getOrCreate(tlsSpec));
        }
        tlsSpecs =  tlsSpecsBuilder.build();
        contexts = sslContextsBuilder.build();
    }

    ClientTlsSpec getClientTlsSpec(SessionProtocol sessionProtocol) {
        final ClientTlsSpec clientTlsSpec = tlsSpecs.get(sessionProtocol);
        checkArgument(clientTlsSpec != null, "Unsupported protocol '%s'. Only TLS-enabled protocols" +
                                             " have a default ClientTlsSpec.", sessionProtocol);
        return clientTlsSpec;
    }

    SslContext getSslContext(SessionProtocol sessionProtocol) {
        final SslContext sslContext = contexts.get(sessionProtocol);
        checkArgument(sslContext != null, "Unsupported protocol '%s'. Only TLS-enabled protocols" +
                                          " have a default ClientTlsSpec.", sessionProtocol);
        return sslContext;
    }

    void release(SslContextFactory factory) {
        for (SslContext sslContext: contexts.values()) {
            factory.release(sslContext);
        }
    }
}
