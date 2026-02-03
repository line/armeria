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

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;

import javax.net.ssl.SSLEngine;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.TlsPeerVerifier;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.Nullable;

final class SanPeerVerifierFactory implements TlsPeerVerifierFactory {

    private final List<SanMatcher> sanMatchers;

    SanPeerVerifierFactory(@Nullable List<SanMatcher> sanMatchers) {
        this.sanMatchers = sanMatchers != null ? ImmutableList.copyOf(sanMatchers) : ImmutableList.of();
    }

    @Override
    public TlsPeerVerifier create(TlsPeerVerifier delegate) {
        return (chain, authType, engine) -> {
            delegate.verify(chain, authType, engine);
            verifySan(chain, engine);
        };
    }

    private void verifySan(X509Certificate[] chain, SSLEngine engine) throws CertificateException {
        if (sanMatchers.isEmpty()) {
            return;
        }
        if (chain == null || chain.length == 0) {
            throw new CertificateException("No peer certificates presented.");
        }
        final X509Certificate leaf = chain[0];
        for (SanMatcher matcher : sanMatchers) {
            if (matcher.matches(leaf)) {
                return;
            }
        }
        throw new CertificateException("Subject alternative name verification failed.");
    }

    @Override
    public int hashCode() {
        return Objects.hash(sanMatchers);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SanPeerVerifierFactory)) {
            return false;
        }
        final SanPeerVerifierFactory that = (SanPeerVerifierFactory) obj;
        return sanMatchers.equals(that.sanMatchers);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("sanMatchers", sanMatchers.size())
                          .toString();
    }
}
