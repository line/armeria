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

import java.util.Optional;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.core.v3.TransportSocket;

/**
 * A snapshot of a {@link TransportSocket} resource with its associated TLS configuration.
 * This snapshot includes optional {@link TlsCertificateSnapshot} and
 * {@link CertificateValidationContextSnapshot} for TLS connections.
 */
@UnstableApi
public final class TransportSocketSnapshot implements Snapshot<TransportSocket> {

    private final TransportSocket transportSocket;
    @Nullable
    private final TlsCertificateSnapshot tlsCertificate;
    @Nullable
    private final CertificateValidationContextSnapshot validationContext;

    TransportSocketSnapshot(TransportSocket transportSocket) {
        this.transportSocket = transportSocket;
        tlsCertificate = null;
        validationContext = null;
    }

    TransportSocketSnapshot(TransportSocket transportSocket,
                            Optional<TlsCertificateSnapshot> tlsCertificate,
                            Optional<CertificateValidationContextSnapshot> validationContext) {
        this.transportSocket = transportSocket;
        this.tlsCertificate = tlsCertificate.orElse(null);
        this.validationContext = validationContext.orElse(null);
    }

    @Override
    public TransportSocket xdsResource() {
        return transportSocket;
    }

    /**
     * Returns the {@link TlsCertificateSnapshot} containing the certificate and private key
     * for this transport socket, or {@code null} if not configured.
     */
    public @Nullable TlsCertificateSnapshot tlsCertificate() {
        return tlsCertificate;
    }

    /**
     * Returns the {@link CertificateValidationContextSnapshot} containing the trusted CA certificates
     * for validating peer certificates, or {@code null} if not configured.
     */
    public @Nullable CertificateValidationContextSnapshot validationContext() {
        return validationContext;
    }
}
