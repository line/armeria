/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.resteasy;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SimpleTrustManagerFactory;

/**
 * Configures {@link SslContextBuilder}.
 */
final class SslContextConfigurator {

    private SslContextConfigurator() {}

    /**
     * Adds SSL/TLS context to the specified {@link SslContextBuilder}.
     */
    static void configureSslContext(SslContextBuilder sslContextBuilder,
                                    @Nullable KeyStore keyStore, @Nullable String keyStorePassword,
                                    @Nullable KeyStore trustStore, boolean isTrustSelfSignedCertificates) {
        try {
            if (trustStore != null) {
                final TrustManagerFactory trustManagerFactory =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(trustStore);
                if (isTrustSelfSignedCertificates) {
                    sslContextBuilder.trustManager(trustSelfSignedFactory(trustManagerFactory));
                } else {
                    sslContextBuilder.trustManager(trustManagerFactory);
                }
            }
            if (keyStore != null) {
                final KeyManagerFactory keyManagerFactory =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore,
                                       keyStorePassword == null ? null : keyStorePassword.toCharArray());
                sslContextBuilder.keyManager(keyManagerFactory);
            }
        } catch (NoSuchAlgorithmException | KeyStoreException | UnrecoverableKeyException e) {
            throw new IllegalStateException("Failed to configure TLS: " + e, e);
        }
    }

    private static TrustManagerFactory trustSelfSignedFactory(TrustManagerFactory trustManagerFactory) {
        final TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
        if (trustManagers == null) {
            return trustManagerFactory;
        } else {
            final TrustManager[] wrappedTrustManagers = new TrustManager[trustManagers.length];
            for (int i = 0; i < wrappedTrustManagers.length; ++i) {
                final TrustManager trustManager = trustManagers[i];
                if (trustManager instanceof X509TrustManager) {
                    wrappedTrustManagers[i] =
                            new TrustSelfSignedX509TrustManager((X509TrustManager)trustManager);
                }
            }
            return new TrustManagerFactoryWrapper(wrappedTrustManagers);
        }
    }

    private static final class TrustSelfSignedX509TrustManager implements X509TrustManager {
        private final X509TrustManager delegate;

        TrustSelfSignedX509TrustManager(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain.length != 1) {
                delegate.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    private static final class TrustManagerFactoryWrapper extends SimpleTrustManagerFactory {
        private final TrustManager[] trustManagers;

        TrustManagerFactoryWrapper(TrustManager[] trustManagers) {
            this.trustManagers = trustManagers;
        }

        @Override
        protected void engineInit(KeyStore keyStore) throws Exception {
        }

        @Override
        protected void engineInit(ManagerFactoryParameters managerFactoryParameters) throws Exception {
        }

        @Override
        protected TrustManager[] engineGetTrustManagers() {
            return trustManagers;
        }
    }
}
