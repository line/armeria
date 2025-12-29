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

import java.security.cert.X509Certificate;
import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;

/**
 * A snapshot of a {@link CertificateValidationContext} resource with its trusted CA certificates.
 * This snapshot is used to validate peer certificates during TLS handshakes.
 */
@UnstableApi
public final class CertificateValidationContextSnapshot implements Snapshot<CertificateValidationContext> {

    private final CertificateValidationContext resource;
    @Nullable
    private final List<X509Certificate> trustedCa;

    CertificateValidationContextSnapshot(CertificateValidationContext resource) {
        this.resource = resource;
        trustedCa = null;
    }

    CertificateValidationContextSnapshot(CertificateValidationContext resource,
                                         @Nullable List<X509Certificate> trustedCa) {
        this.resource = resource;
        this.trustedCa = trustedCa;
    }

    @Override
    public CertificateValidationContext xdsResource() {
        return resource;
    }

    /**
     * Returns the list of trusted CA {@link X509Certificate}s used to validate peer certificates,
     * or {@code null} if not configured.
     */
    public @Nullable List<X509Certificate> trustedCa() {
        return trustedCa;
    }
}
