/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import static com.google.common.base.MoreObjects.firstNonNull;

import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.function.Supplier;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ResourceUtils;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.Ssl;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;

/**
 * A utility class which is used to configure a {@link ServerBuilder} with the tls settings.
 */
public final class ArmeriaConfigurationTlsUtil {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaConfigurationTlsUtil.class);

    private static final String[] EMPTY_PROTOCOL_NAMES = new String[0];

    /**
     * Adds SSL/TLS context to the specified {@link ServerBuilder}.
     */
    public static void configureTls(ServerBuilder sb, Ssl ssl) {
        configureTls(sb, ssl, null, null);
    }

    /**
     * Adds SSL/TLS context to the specified {@link ServerBuilder}.
     */
    public static void configureTls(ServerBuilder sb, Ssl ssl, @Nullable Supplier<KeyStore> keyStoreSupplier,
                                    @Nullable Supplier<KeyStore> trustStoreSupplier) {
        if (!ssl.isEnabled()) {
            return;
        }

        try {
            if (keyStoreSupplier == null && trustStoreSupplier == null &&
                ssl.getKeyStore() == null && ssl.getTrustStore() == null) {
                logger.warn("Configuring TLS with a self-signed certificate " +
                            "because no key or trust store was specified");
                sb.tlsSelfSigned();
                return;
            }
            final KeyManagerFactory keyManagerFactory = getKeyManagerFactory(ssl, keyStoreSupplier);
            final TrustManagerFactory trustManagerFactory = getTrustManagerFactory(ssl, trustStoreSupplier);

            sb.tls(keyManagerFactory);
            sb.tlsCustomizer(sslContextBuilder -> {
                sslContextBuilder.trustManager(trustManagerFactory);

                final SslProvider sslProvider = ssl.getProvider();
                if (sslProvider != null) {
                    sslContextBuilder.sslProvider(sslProvider);
                }
                final List<String> enabledProtocols = ssl.getEnabledProtocols();
                if (enabledProtocols != null) {
                    sslContextBuilder.protocols(enabledProtocols.toArray(EMPTY_PROTOCOL_NAMES));
                }
                final List<String> ciphers = ssl.getCiphers();
                if (ciphers != null) {
                    sslContextBuilder.ciphers(ImmutableList.copyOf(ciphers),
                                              SupportedCipherSuiteFilter.INSTANCE);
                }
                final ClientAuth clientAuth = ssl.getClientAuth();
                if (clientAuth != null) {
                    sslContextBuilder.clientAuth(clientAuth);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure TLS: " + e, e);
        }
    }

    private static KeyManagerFactory getKeyManagerFactory(
            Ssl ssl, @Nullable Supplier<KeyStore> sslStoreProvider) throws Exception {
        final KeyStore store;
        if (sslStoreProvider != null) {
            store = sslStoreProvider.get();
        } else {
            store = loadKeyStore(ssl.getKeyStoreType(), ssl.getKeyStore(), ssl.getKeyStorePassword());
        }

        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        if (ssl.getKeyAlias() != null) {
            keyManagerFactory = new CustomAliasKeyManagerFactory(keyManagerFactory, ssl.getKeyAlias());
        }

        String keyPassword = ssl.getKeyPassword();
        if (keyPassword == null) {
            keyPassword = ssl.getKeyStorePassword();
        }

        keyManagerFactory.init(store, keyPassword != null ? keyPassword.toCharArray()
                                                          : null);
        return keyManagerFactory;
    }

    private static TrustManagerFactory getTrustManagerFactory(
            Ssl ssl, @Nullable Supplier<KeyStore> sslStoreProvider) throws Exception {
        final KeyStore store;
        if (sslStoreProvider != null) {
            store = sslStoreProvider.get();
        } else {
            store = loadKeyStore(ssl.getTrustStoreType(), ssl.getTrustStore(), ssl.getTrustStorePassword());
        }

        final TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(store);
        return trustManagerFactory;
    }

    @Nullable
    private static KeyStore loadKeyStore(
            @Nullable String type,
            @Nullable String resource,
            @Nullable String password) throws IOException, GeneralSecurityException {
        if (resource == null) {
            return null;
        }
        final KeyStore store = KeyStore.getInstance(firstNonNull(type, "JKS"));
        final URL url = ResourceUtils.getURL(resource);
        store.load(url.openStream(), password != null ? password.toCharArray()
                                                      : null);
        return store;
    }

    private ArmeriaConfigurationTlsUtil() {
    }
}
