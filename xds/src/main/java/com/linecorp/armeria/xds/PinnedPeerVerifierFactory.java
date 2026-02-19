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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;

import javax.net.ssl.SSLEngine;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.TlsPeerVerifier;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.Nullable;

final class PinnedPeerVerifierFactory implements TlsPeerVerifierFactory {

    private final List<ByteString> spkiPins;
    private final List<ByteString> certHashPins;

    PinnedPeerVerifierFactory(@Nullable List<ByteString> spkiPins,
                              @Nullable List<ByteString> certHashPins) {
        this.spkiPins = spkiPins != null ? ImmutableList.copyOf(spkiPins) : ImmutableList.of();
        this.certHashPins = certHashPins != null ? ImmutableList.copyOf(certHashPins) : ImmutableList.of();
    }

    @Override
    public TlsPeerVerifier create(TlsPeerVerifier delegate) {
        return (chain, authType, engine) -> {
            delegate.verify(chain, authType, engine);
            verifyPins(chain, engine);
        };
    }

    private void verifyPins(X509Certificate[] chain, SSLEngine engine) throws CertificateException {
        if (chain.length == 0) {
            throw new CertificateException("No peer certificates presented.");
        }
        if (spkiPins.isEmpty() && certHashPins.isEmpty()) {
            return;
        }
        final X509Certificate leaf = chain[0];
        final boolean spkiMatched = !spkiPins.isEmpty() && matchesSpki(leaf);
        final boolean certMatched = !certHashPins.isEmpty() && matchesCertHash(leaf);
        if (spkiMatched || certMatched) {
            return;
        }
        throw new CertificateException("Certificate pin check failed.");
    }

    private boolean matchesSpki(X509Certificate cert) throws CertificateException {
        final byte[] spki = cert.getPublicKey().getEncoded();
        final ByteString digest = ByteString.copyFrom(sha256(spki));
        for (ByteString pin : spkiPins) {
            if (pin.equals(digest)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCertHash(X509Certificate cert) throws CertificateException {
        final ByteString digest = ByteString.copyFrom(sha256(cert.getEncoded()));
        for (ByteString pin : certHashPins) {
            if (pin.equals(digest)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] sha256(byte[] input) throws CertificateException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException("SHA-256 is not available.", e);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(spkiPins, certHashPins);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PinnedPeerVerifierFactory)) {
            return false;
        }
        final PinnedPeerVerifierFactory that = (PinnedPeerVerifierFactory) obj;
        return spkiPins.equals(that.spkiPins) &&
               certHashPins.equals(that.certHashPins);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("spkiPins", spkiPins.size())
                          .add("certHashPins", certHashPins.size())
                          .toString();
    }
}
