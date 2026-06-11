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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.util.Mapping;

/**
 * A {@link ServerTlsProvider} backed by a static hostname→{@link ServerTlsSpec} mapping
 * built from VirtualHost configurations at server build time.
 */
final class StaticTlsProvider implements ServerTlsProvider {

    private final Mapping<String, ServerTlsSpec> specMapping;

    /**
     * Creates a {@link StaticTlsProvider} from VirtualHosts, or returns {@code null} if
     * no VirtualHost has a {@link ServerTlsSpec}.
     */
    @Nullable
    static StaticTlsProvider of(VirtualHost defaultVirtualHost, List<VirtualHost> virtualHosts) {
        ServerTlsSpec defaultSpec = defaultVirtualHost.serverTlsSpec();
        if (defaultSpec == null) {
            for (VirtualHost vh : virtualHosts) {
                if (vh.serverTlsSpec() != null) {
                    defaultSpec = vh.serverTlsSpec();
                    break;
                }
            }
        }
        if (defaultSpec == null) {
            return null;
        }
        return new StaticTlsProvider(buildMapping(defaultSpec, virtualHosts));
    }

    private static Mapping<String, ServerTlsSpec> buildMapping(
            ServerTlsSpec defaultSpec, List<VirtualHost> virtualHosts) {
        final DomainMappingBuilder<ServerTlsSpec> builder = new DomainMappingBuilder<>(defaultSpec);
        for (VirtualHost vh : virtualHosts) {
            final ServerTlsSpec spec = vh.serverTlsSpec();
            if (spec != null) {
                final String pattern = vh.originalHostnamePattern();
                if (!"*".equals(pattern)) {
                    builder.add(pattern, spec);
                }
            }
        }
        return builder.build();
    }

    private StaticTlsProvider(Mapping<String, ServerTlsSpec> specMapping) {
        this.specMapping = specMapping;
    }

    @Override
    public CompletableFuture<@Nullable ServerTlsSpec> serverTlsSpec(ConnectionContext ctx) {
        return UnmodifiableFuture.completedFuture(specMapping.map(ctx.sniHostname()));
    }
}
