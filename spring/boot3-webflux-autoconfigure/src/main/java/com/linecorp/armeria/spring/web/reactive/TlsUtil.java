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
package com.linecorp.armeria.spring.web.reactive;

import java.security.KeyStore;
import java.util.function.Supplier;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.web.server.WebServerSslBundle;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.Ssl;

final class TlsUtil {

    static void configureTls(ServerBuilder sb, Ssl ssl,
                             ArmeriaReactiveWebServerFactory factory) {
        if (!ssl.isEnabled()) {
            return;
        }
        final org.springframework.boot.web.server.Ssl springSsl = factory.getSsl();
        final Supplier<KeyStore> keyStoreSupplier;
        final Supplier<KeyStore> trustStoreSupplier;
        if ((springSsl.getCertificate() != null && springSsl.getCertificatePrivateKey() != null) || // PEM
            ssl.getKeyStore() != null || "PKCS11".equals(ssl.getKeyStoreType())) { // JSK
            final SslBundle sslBundle = WebServerSslBundle.get(springSsl, factory.getSslBundles());
            keyStoreSupplier = () -> {
                try {
                    return sslBundle.getStores().getKeyStore();
                } catch (Exception e) {
                    return Exceptions.throwUnsafely(e);
                }
            };
            trustStoreSupplier = () -> {
                try {
                    return sslBundle.getStores().getTrustStore();
                } catch (Exception e) {
                    return Exceptions.throwUnsafely(e);
                }
            };
        } else {
            keyStoreSupplier = null;
            trustStoreSupplier = null;
        }
        ArmeriaConfigurationUtil.configureTls(sb, ssl, keyStoreSupplier, trustStoreSupplier);
    }

    private TlsUtil() {}
}
