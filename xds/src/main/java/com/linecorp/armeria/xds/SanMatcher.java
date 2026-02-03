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

import java.net.InetAddress;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SubjectAltNameMatcher;

final class SanMatcher {

    private final SubjectAltNameMatcher.SanType type;
    private final StringMatcherImpl matcher;

    SanMatcher(SubjectAltNameMatcher.SanType type, StringMatcherImpl matcher) {
        this.type = Objects.requireNonNull(type, "type");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    boolean matches(X509Certificate certificate) throws CertificateException {
        final Collection<List<?>> altNames;
        try {
            altNames = certificate.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            throw new CertificateException("Failed to parse subject alternative names", e);
        }
        if (altNames == null) {
            return false;
        }
        for (List<?> altName : altNames) {
            final Integer type = altName.size() >= 2 ? (Integer) altName.get(0) : null;
            if (type == null || !matchesType(type)) {
                continue;
            }
            final Object value = altName.get(1);
            final String valueString = toValueString(value);
            if (valueString == null) {
                continue;
            }
            if (this.type == SubjectAltNameMatcher.SanType.DNS &&
                matcher.isExact() && matcher.exact() != null) {
                if (dnsExactMatchWithWildcard(valueString, matcher.exact(), matcher.ignoreCase())) {
                    return true;
                }
            } else if (matcher.match(valueString)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesType(int altNameType) {
        switch (type) {
            case EMAIL:
                return altNameType == 1;
            case DNS:
                return altNameType == 2;
            case URI:
                return altNameType == 6;
            case IP_ADDRESS:
                return altNameType == 7;
            default:
                return false;
        }
    }

    @Nullable
    private static String toValueString(Object value) throws CertificateException {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof byte[]) {
            try {
                return InetAddress.getByAddress((byte[]) value).getHostAddress();
            } catch (Exception e) {
                throw new CertificateException("Failed to parse IP address SAN", e);
            }
        }
        return null;
    }

    private static boolean dnsExactMatchWithWildcard(String sanValue, String expected,
                                                     boolean ignoreCase) {
        String san = sanValue;
        String exp = expected;
        if (ignoreCase) {
            san = Ascii.toLowerCase(san);
            exp = Ascii.toLowerCase(exp);
        }
        if (!san.contains("*")) {
            return san.equals(exp);
        }
        if (!san.startsWith("*.") || san.indexOf('*', 1) != -1) {
            return false;
        }
        final int firstDot = exp.indexOf('.');
        if (firstDot <= 0) {
            return false;
        }
        return exp.substring(firstDot).equals(san.substring(1));
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, matcher);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SanMatcher)) {
            return false;
        }
        final SanMatcher that = (SanMatcher) obj;
        return type == that.type && matcher.equals(that.matcher);
    }

    @Override
    public String toString() {
        return "SanMatcher{" + "type=" + type + ", matcher=" + matcher + '}';
    }
}
