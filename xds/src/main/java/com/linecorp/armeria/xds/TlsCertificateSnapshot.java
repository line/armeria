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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;

/**
 * A snapshot of a {@link TlsCertificate} resource with its associated {@link TlsKeyPair}.
 * This snapshot is created when resolving TLS certificate configuration from xDS resources.
 */
@UnstableApi
public final class TlsCertificateSnapshot implements Snapshot<TlsCertificate> {

    private final TlsCertificate resource;
    @Nullable
    private final TlsKeyPair tlsKeyPair;

    TlsCertificateSnapshot(TlsCertificate resource, @Nullable TlsKeyPair tlsKeyPair) {
        this.resource = resource;
        this.tlsKeyPair = tlsKeyPair;
    }

    /**
     * Returns the {@link TlsKeyPair} containing the certificate and private key,
     * or {@code null} if not available.
     */
    @Nullable
    public TlsKeyPair tlsKeyPair() {
        return tlsKeyPair;
    }

    @Override
    public TlsCertificate xdsResource() {
        return resource;
    }
}
