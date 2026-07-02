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

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ConnectionContext;
import com.linecorp.armeria.server.ServerTlsSpec;

/**
 * Selects the best-matching {@link ServerTlsSpec} for a connection using SNI-based
 * certificate selection, following Envoy's
 * <a href="https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/security/ssl#arch-overview-ssl-cert-select">
 * certificate selection</a> algorithm.
 *
 * <p>Selection order:
 * <ol>
 *   <li>Exact SNI match against certificate DNS SANs (or CN if no SANs)</li>
 *   <li>Wildcard match (one level only, e.g. {@code *.example.com})</li>
 *   <li>Fallback to the first certificate</li>
 * </ol>
 *
 * <p>The server name map follows Envoy's {@code DefaultTlsCertificateSelector}: exact names
 * are stored as-is, wildcard names (e.g. {@code *.example.com}) are stored as
 * {@code .example.com}. The first certificate for a given name wins.
 */
final class ServerTlsSpecSelector {

    private final ImmutableList<ServerTlsSpec> specs;
    private final Map<String, ServerTlsSpec> serverNameMap;

    ServerTlsSpecSelector(List<ServerTlsSpec> specs,
                          List<TlsCertificateSnapshot> tlsCertificates) {
        this.specs = ImmutableList.copyOf(specs);
        serverNameMap = buildServerNameMap(specs, tlsCertificates);
    }

    @Nullable
    ServerTlsSpec select(ConnectionContext ctx) {
        if (specs.isEmpty()) {
            return null;
        }
        final String sni = ctx.sniHostname();
        if (sni != null && !sni.isEmpty()) {
            final String normalizedSni = sni.toLowerCase(Locale.ROOT);
            // Exact match
            final ServerTlsSpec exact = serverNameMap.get(normalizedSni);
            if (exact != null) {
                return exact;
            }
            // Wildcard match: "www.example.com" → ".example.com"
            final int dotPos = normalizedSni.indexOf('.', 1);
            if (dotPos > 0 && dotPos < normalizedSni.length() - 1) {
                final ServerTlsSpec wildcard = serverNameMap.get(normalizedSni.substring(dotPos));
                if (wildcard != null) {
                    return wildcard;
                }
            }
        }
        return specs.get(0);
    }

    private static Map<String, ServerTlsSpec> buildServerNameMap(
            List<ServerTlsSpec> specs, List<TlsCertificateSnapshot> tlsCertificates) {
        final Map<String, ServerTlsSpec> nameMap = new HashMap<>();
        for (int i = 0; i < tlsCertificates.size(); i++) {
            final TlsKeyPair keyPair = tlsCertificates.get(i).tlsKeyPair();
            if (keyPair == null || keyPair.certificateChain().isEmpty()) {
                continue;
            }
            final ServerTlsSpec spec = specs.get(i);
            final X509Certificate firstCert = keyPair.certificateChain().get(0);
            for (String name : extractDnsNames(firstCert)) {
                final String key = name.startsWith("*.") ? name.substring(1) : name;
                nameMap.putIfAbsent(key, spec);
            }
        }
        return ImmutableMap.copyOf(nameMap);
    }

    // Uses DNS SANs if present; falls back to CN per RFC 6125 §6.4.4.
    static List<String> extractDnsNames(X509Certificate cert) {
        final Collection<List<?>> sans;
        try {
            sans = cert.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return Exceptions.throwUnsafely(e);
        }
        if (sans != null) {
            final ImmutableList.Builder<String> dnsNames = ImmutableList.builder();
            for (List<?> san : sans) {
                if (san.size() >= 2 && Objects.equals(2, san.get(0))) {
                    dnsNames.add(((String) san.get(1)).toLowerCase(Locale.ROOT));
                }
            }
            return dnsNames.build();
        }
        final String cn = extractCn(cert);
        if (cn != null) {
            return ImmutableList.of(cn.toLowerCase(Locale.ROOT));
        }
        return ImmutableList.of();
    }

    @Nullable
    private static String extractCn(X509Certificate cert) {
        final String dn = cert.getSubjectX500Principal().getName();
        for (String rdn : dn.split(",")) {
            final String trimmed = rdn.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return null;
    }
}
