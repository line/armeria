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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * An implementation of {@link X509ExtendedTrustManager} that skips verification on whitelisted hosts.
 */
public final class IgnoreHostsTrustManager extends X509ExtendedTrustManager {

    private final Set<String> insecureHosts;
    private X509ExtendedTrustManager delegate;

    private IgnoreHostsTrustManager(Set<String> insecureHosts) {
        try {
            final TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
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
        requireNonNull(delegate, "default X509ExtendedTrustManager");
        this.insecureHosts = insecureHosts;
    }

    /**
     * Returns new {@link IgnoreHostsTrustManager} instance.
     */
    public static IgnoreHostsTrustManager from(String... insecureHosts) {
        return new IgnoreHostsTrustManager(new HashSet<>(Arrays.asList(insecureHosts)));
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String s, Socket socket)
            throws CertificateException {
        if (!insecureHosts.contains(socket.getInetAddress().getHostName())) {
            delegate.checkServerTrusted(x509Certificates, s, socket);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine)
            throws CertificateException {
        if (!insecureHosts.contains(sslEngine.getPeerHost())) {
            delegate.checkServerTrusted(x509Certificates, s, sslEngine);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String s, Socket socket)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
            throws CertificateException {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
