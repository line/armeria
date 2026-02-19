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

import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SubjectAltNameMatcher;

/**
 * A snapshot of a {@link CertificateValidationContext} resource with its trusted CA certificates.
 * This snapshot is used to validate peer certificates during TLS handshakes.
 */
@UnstableApi
public final class CertificateValidationContextSnapshot implements Snapshot<CertificateValidationContext> {

    private final CertificateValidationContext resource;
    @Nullable
    private final List<X509Certificate> trustedCa;
    private final List<ByteString> verifyCertificateSpkiPins;
    private final List<ByteString> verifyCertificateHashPins;
    private final List<SanMatcher> typedSanMatchers;

    CertificateValidationContextSnapshot(CertificateValidationContext resource,
                                         @Nullable List<X509Certificate> trustedCa) {
        this.resource = requireNonNull(resource, "resource");
        this.trustedCa = trustedCa;
        verifyCertificateSpkiPins = decodeSpkiPins(resource.getVerifyCertificateSpkiList());
        verifyCertificateHashPins = decodeCertificateHashPins(resource.getVerifyCertificateHashList());
        final List<SanMatcher> parsedSanMatchers = new ArrayList<>();
        for (SubjectAltNameMatcher matcher : resource.getMatchTypedSubjectAltNamesList()) {
            final SubjectAltNameMatcher.SanType sanType = matcher.getSanType();
            if (sanType == SubjectAltNameMatcher.SanType.OTHER_NAME) {
                throw new IllegalArgumentException("Unsupported SAN type: OTHER_NAME");
            }
            parsedSanMatchers.add(new SanMatcher(sanType,
                                                 new StringMatcherImpl(matcher.getMatcher())));
        }
        typedSanMatchers = ImmutableList.copyOf(parsedSanMatchers);
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

    /**
     * Returns the {@link TlsPeerVerifierFactory}s used to validate peer certificates.
     */
    public List<TlsPeerVerifierFactory> peerVerifierFactories() {
        final ImmutableList.Builder<TlsPeerVerifierFactory> builder = ImmutableList.builder();
        if (!verifyCertificateSpkiPins.isEmpty() || !verifyCertificateHashPins.isEmpty()) {
            builder.add(new PinnedPeerVerifierFactory(verifyCertificateSpkiPins,
                                                      verifyCertificateHashPins
            ));
        }
        if (!typedSanMatchers.isEmpty()) {
            builder.add(new SanPeerVerifierFactory(typedSanMatchers));
        }
        return builder.build();
    }

    private static List<ByteString> decodeSpkiPins(List<String> pins) {
        if (pins.isEmpty()) {
            return Collections.emptyList();
        }
        final List<ByteString> decoded = new ArrayList<>(pins.size());
        for (String pin : pins) {
            decoded.add(ByteString.copyFrom(Base64.getDecoder().decode(pin)));
        }
        return ImmutableList.copyOf(decoded);
    }

    private static List<ByteString> decodeCertificateHashPins(List<String> pins) {
        if (pins.isEmpty()) {
            return Collections.emptyList();
        }
        final List<ByteString> decoded = new ArrayList<>(pins.size());
        for (String pin : pins) {
            decoded.add(decodeHex(pin));
        }
        return ImmutableList.copyOf(decoded);
    }

    private static ByteString decodeHex(String pin) {
        final String normalized = pin.replace(":", "").toUpperCase(Locale.ROOT);
        if (normalized.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid certificate hash length: " + pin);
        }
        return ByteString.copyFrom(BaseEncoding.base16().decode(normalized));
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final CertificateValidationContextSnapshot that = (CertificateValidationContextSnapshot) object;
        return Objects.equal(resource, that.resource);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(resource);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("resource", resource)
                          .toString();
    }
}
