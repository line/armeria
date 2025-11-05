/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.internal.common.TlsProviderUtil.normalizeHostname;
import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

final class MappedTlsProvider implements TlsProvider {

    private final Map<String, TlsKeyPair> tlsKeyPairs;
    private final Map<String, List<X509Certificate>> trustedCertificates;

    MappedTlsProvider(Map<String, TlsKeyPair> tlsKeyPairs,
                      Map<String, List<X509Certificate>> trustedCertificates) {
        this.tlsKeyPairs = tlsKeyPairs;
        this.trustedCertificates = trustedCertificates;
    }

    @Nullable
    @Override
    public TlsKeyPair keyPair(String hostname) {
        requireNonNull(hostname, "hostname");
        return find(hostname, tlsKeyPairs);
    }

    @Override
    public List<X509Certificate> trustedCertificates(String hostname) {
        final List<X509Certificate> certs = find(hostname, trustedCertificates);
        return firstNonNull(certs, ImmutableList.of());
    }

    @Nullable
    private static <T> T find(String hostname, Map<String, T> map) {
        if ("*".equals(hostname)) {
            return map.get("*");
        }
        hostname = normalizeHostname(hostname);

        T value = map.get(hostname);
        if (value != null) {
            return value;
        }

        // No exact match, let's try a wildcard match.
        final int idx = hostname.indexOf('.');
        if (idx != -1) {
            value = map.get(hostname.substring(idx));
            if (value != null) {
                return value;
            }
        }
        // Try to find the default one.
        return map.get("*");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MappedTlsProvider)) {
            return false;
        }
        final MappedTlsProvider that = (MappedTlsProvider) o;
        return tlsKeyPairs.equals(that.tlsKeyPairs) &&
               trustedCertificates.equals(that.trustedCertificates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tlsKeyPairs, trustedCertificates);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("tlsKeyPairs", tlsKeyPairs)
                          .add("trustedCertificates", trustedCertificates)
                          .toString();
    }
}
