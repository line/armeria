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

package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AbstractTlsProviderBuilder;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * A builder class for creating a {@link TlsProvider} that provides client-side TLS.
 */
@UnstableApi
public final class ClientTlsProviderBuilder extends AbstractTlsProviderBuilder<ClientTlsProviderBuilder> {

    private boolean tlsNoVerifySet;
    private final Set<String> insecureHosts = new HashSet<>();

    /**
     * Set the default {@link TlsKeyPair} for
     * <a href="https://www.cloudflare.com/learning/access-management/what-is-mutual-tls/">client certificate authentication</a>
     * which is used when no {@link TlsKeyPair} is specified for a hostname.
     */
    @Override
    public ClientTlsProviderBuilder setDefault(TlsKeyPair tlsKeyPair) {
        return super.setDefault(tlsKeyPair);
    }

    /**
     * Set the {@link TlsKeyPair} for
     * <a href="https://www.cloudflare.com/learning/access-management/what-is-mutual-tls/">client certificate authentication</a>
     * when performing TLS handshake with the specified (optionally wildcard) {@code hostname}.
     *
     * <p><a href="https://en.wikipedia.org/wiki/Wildcard_DNS_record">DNS wildcard</a> is supported as hostname.
     * The wildcard will only match one sub-domain deep and only when wildcard is used as the most-left label.
     * For example, *.armeria.dev will match foo.armeria.dev but NOT bar.foo.armeria.dev
     *
     * <p>Note that {@code "*"} is a special hostname which matches any hostname.
     */
    @Override
    public ClientTlsProviderBuilder set(String hostname, TlsKeyPair tlsKeyPair) {
        return super.set(hostname, tlsKeyPair);
    }

    /**
     * Disables the verification of server's TLS certificate chain. If you want to disable verification for
     * only specific hosts, use {@link #tlsNoVerifyHosts(String...)}.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see InsecureTrustManagerFactory
     * @see #tlsCustomizer(Consumer)
     */
    public ClientTlsProviderBuilder tlsNoVerify() {
        tlsNoVerifySet = true;
        checkState(insecureHosts.isEmpty(), "tlsNoVerify() and tlsNoVerifyHosts() are mutually exclusive.");
        return this;
    }

    /**
     * Disables the verification of server's TLS certificate chain for specific hosts. If you want to disable
     * all verification, use {@link #tlsNoVerify()} .
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ClientTlsProviderBuilder tlsNoVerifyHosts(String... insecureHosts) {
        requireNonNull(insecureHosts, "insecureHosts");
        return tlsNoVerifyHosts(ImmutableList.copyOf(insecureHosts));
    }

    /**
     * Disables the verification of server's TLS certificate chain for specific hosts. If you want to disable
     * all verification, use {@link #tlsNoVerify()} .
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ClientTlsProviderBuilder tlsNoVerifyHosts(Iterable<String> insecureHosts) {
        requireNonNull(insecureHosts, "insecureHosts");
        checkState(!tlsNoVerifySet, "tlsNoVerify() and tlsNoVerifyHosts() are mutually exclusive.");
        insecureHosts.forEach(this.insecureHosts::add);
        return this;
    }

    @Override
    public TlsProvider build() {
        if (tlsNoVerifySet) {
            tlsCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE));
        } else if (!insecureHosts.isEmpty()) {
            tlsCustomizer(b -> b.trustManager(IgnoreHostsTrustManager.of(insecureHosts)));
        }
        return super.build();
    }
}
