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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;

import com.google.common.base.MoreObjects;

final class StaticTlsProvider implements TlsProvider {

    private final TlsKeyPair tlsKeyPair;
    private final List<X509Certificate> trustedCertificates;

    StaticTlsProvider(TlsKeyPair tlsKeyPair, List<X509Certificate> trustedCertificates) {
        requireNonNull(tlsKeyPair, "tlsKeyPair");
        requireNonNull(trustedCertificates, "trustedCertificates");
        this.tlsKeyPair = tlsKeyPair;
        this.trustedCertificates = trustedCertificates;
    }

    @Override
    public TlsKeyPair find(String hostname) {
        return tlsKeyPair;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StaticTlsProvider)) {
            return false;
        }
        final StaticTlsProvider that = (StaticTlsProvider) o;
        return tlsKeyPair.equals(that.tlsKeyPair) &&
               trustedCertificates.equals(that.trustedCertificates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tlsKeyPair, trustedCertificates);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("tlsKeyPair", tlsKeyPair)
                          .add("trustedCertificates", trustedCertificates)
                          .toString();
    }
}
