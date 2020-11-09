/*
 *  Copyright 2020 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
/*
 *  Copyright (C) 2020 Square, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Set;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import com.google.common.collect.ImmutableSet;

/**
 * An implementation of {@link X509ExtendedTrustManager} that skips verification on the list of allowed hosts.
 */
final class IgnoreHostsTrustManager extends X509ExtendedTrustManager {

    // Forked from okhttp-4.9.0
    // https://github.com/square/okhttp/blob/1364ea44ae1f1c4b5a1cc32e4e7b51d23cb78517/okhttp-tls/src/main/kotlin/okhttp3/tls/internal/InsecureExtendedTrustManager.kt

    /**
     * Returns new {@link IgnoreHostsTrustManager} instance.
     */
    static IgnoreHostsTrustManager of(Set<String> insecureHosts) {
        X509ExtendedTrustManager delegate = null;
        try {
            final TrustManagerFactory trustManagerFactory = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            final TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            for (TrustManager tm : trustManagers) {
                if (tm instanceof X509ExtendedTrustManager) {
                    delegate = (X509ExtendedTrustManager) tm;
                    break;
                }
            }
        } catch (GeneralSecurityException ignored) {
            // ignore
        }
        requireNonNull(delegate, "cannot resolve default trust manager");
        return new IgnoreHostsTrustManager(delegate, ImmutableSet.copyOf(insecureHosts));
    }

    private final X509ExtendedTrustManager delegate;
    private final Set<String> insecureHosts;

    IgnoreHostsTrustManager(X509ExtendedTrustManager delegate, Set<String> insecureHosts) {
        this.delegate = delegate;
        this.insecureHosts = insecureHosts;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String authType, Socket socket)
            throws CertificateException {
        if (!insecureHosts.contains(((InetSocketAddress) socket.getRemoteSocketAddress()).getHostString())) {
            delegate.checkServerTrusted(x509Certificates, authType, socket);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String authType, SSLEngine sslEngine)
            throws CertificateException {
        if (!insecureHosts.contains(sslEngine.getPeerHost())) {
            delegate.checkServerTrusted(x509Certificates, authType, sslEngine);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String authType)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String authType, Socket socket)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String authType, SSLEngine sslEngine)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String authType)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
