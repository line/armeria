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

import static java.util.Objects.requireNonNull;

/**
 * A {@link ClientTlsProvider} that resolves TLS specs from the pre-computed
 * {@link BootstrapSslContexts} based on the session protocol.
 */
final class BootstrapClientTlsProvider implements ClientTlsProvider {

    private final BootstrapSslContexts bootstrapSslContexts;

    BootstrapClientTlsProvider(BootstrapSslContexts bootstrapSslContexts) {
        this.bootstrapSslContexts = requireNonNull(bootstrapSslContexts, "bootstrapSslContexts");
    }

    @Override
    public ClientTlsSpec clientTlsSpec(ClientRequestContext ctx) {
        return bootstrapSslContexts.getClientTlsSpec(ctx.sessionProtocol());
    }
}
