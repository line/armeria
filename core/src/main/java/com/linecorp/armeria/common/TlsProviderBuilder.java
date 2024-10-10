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

import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.internal.common.TlsProviderUtil;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * A builder for {@link TlsProvider}.
 *
 * @see ClientFactoryBuilder#tlsProvider(TlsProvider)
 * @see ServerBuilder#tlsProvider(TlsProvider)
 */
public final class TlsProviderBuilder {

    private final ImmutableMap.Builder<String, TlsKeyPair> tlsKeyPairsBuilder = ImmutableMap.builder();
    private final ImmutableMap.Builder<String, List<X509Certificate>> x509CertificateBuilder =
            ImmutableMap.builder();

    /**
     * Creates a new instance.
     */
    TlsProviderBuilder() {}

    /**
     * Sets the {@link TlsKeyPair} for the specified (optionally wildcard) {@code hostname}.
     *
     * <p><a href="https://en.wikipedia.org/wiki/Wildcard_DNS_record">DNS wildcard</a> is supported as hostname.
     * The wildcard will only match one sub-domain deep and only when wildcard is used as the most-left label.
     * For example, *.armeria.dev will match foo.armeria.dev but NOT bar.foo.armeria.dev
     *
     * <p>Note that {@code "*"} is a special hostname which matches any hostname which may be used to find the
     * {@link TlsKeyPair} for the {@linkplain ServerBuilder#defaultVirtualHost() default virtual host}.
     *
     * <p>The {@link TlsKeyPair} will be used for
     * <a href="https://www.cloudflare.com/learning/access-management/what-is-mutual-tls/">client certificate authentication</a>
     * when it is used for a client.
     */
    public TlsProviderBuilder keyPair(String hostname, TlsKeyPair tlsKeyPair) {
        requireNonNull(hostname, "hostname");
        requireNonNull(tlsKeyPair, "tlsKeyPair");
        tlsKeyPairsBuilder.put(normalize(hostname), tlsKeyPair);
        return this;
    }

    /**
     * Sets the default {@link TlsKeyPair} which is used when no {@link TlsKeyPair} is specified for a hostname.
     *
     * <p>The {@link TlsKeyPair} will be used for
     * <a href="https://www.cloudflare.com/learning/access-management/what-is-mutual-tls/">client certificate authentication</a>
     * when it is used for a client.
     */
    public TlsProviderBuilder keyPair(TlsKeyPair tlsKeyPair) {
        return keyPair("*", tlsKeyPair);
    }

    /**
     * Sets the specified {@link X509Certificate}s to the trusted certificates that will be used for verifying
     * the remote endpoint's certificate.
     *
     * <p>The system default will be used if no specific trusted certificates are set for a hostname and no
     * default trusted certificates are set.
     */
    public TlsProviderBuilder trustedCertificates(String hostname, X509Certificate... trustedCertificates) {
        requireNonNull(trustedCertificates, "trustedCertificates");
        return trustedCertificates(hostname, ImmutableList.copyOf(trustedCertificates));
    }

    /**
     * Sets the specified {@link X509Certificate}s to the trusted certificates that will be used for verifying
     * the specified {@code hostname}'s certificate.
     *
     * <p>The system default will be used if no specific trusted certificates are set for a hostname and no
     * default trusted certificates are set.
     */
    public TlsProviderBuilder trustedCertificates(String hostname,
                                                  Iterable<? extends X509Certificate> trustedCertificates) {
        requireNonNull(hostname, "hostname");
        requireNonNull(trustedCertificates, "trustedCertificates");
        x509CertificateBuilder.put(normalize(hostname), ImmutableList.copyOf(trustedCertificates));
        return this;
    }

    /**
     * Sets the default {@link X509Certificate}s to the trusted certificates that is used for verifying
     * the remote endpoint's certificate if no specific trusted certificates are set for a hostname.
     *
     * <p>The system default will be used if no specific trusted certificates are set for a hostname and no
     * default trusted certificates are set.
     */
    public TlsProviderBuilder trustedCertificates(X509Certificate... trustedCertificates) {
        requireNonNull(trustedCertificates, "trustedCertificates");
        return trustedCertificates(ImmutableList.copyOf(trustedCertificates));
    }

    /**
     * Sets the default {@link X509Certificate}s to the trusted certificates that is used for verifying
     * the remote endpoint's certificate if no specific trusted certificates are set for a hostname.
     *
     * <p>The system default will be used if no specific trusted certificates are set for a hostname and no
     * default trusted certificates are set.
     */
    public TlsProviderBuilder trustedCertificates(Iterable<? extends X509Certificate> trustedCertificates) {
        return trustedCertificates("*", trustedCertificates);
    }

    private static String normalize(String hostname) {
        if ("*".equals(hostname)) {
            return "*";
        } else {
            return TlsProviderUtil.normalizeHostname(hostname);
        }
    }

    /**
     * Returns a newly-created {@link TlsProvider} instance.
     */
    public TlsProvider build() {
        final Map<String, TlsKeyPair> keyPairMappings = tlsKeyPairsBuilder.build();
        if (keyPairMappings.isEmpty()) {
            throw new IllegalStateException("No TLS key pair is set.");
        }

        final Map<String, List<X509Certificate>> trustedCerts = x509CertificateBuilder.build();
        if (keyPairMappings.size() == 1 && keyPairMappings.containsKey("*") && trustedCerts.isEmpty()) {
            return new StaticTlsProvider(keyPairMappings.get("*"));
        }

        return new MappedTlsProvider(keyPairMappings, trustedCerts);
    }
}
