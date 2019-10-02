/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

class ServerTlsTest {

    @Test
    void testJksKeyStoreWithNullPassword() throws Exception {
        /*
         * Dummy keystore generation
         * keytool -genkeypair -keyalg RSA -keysize 2048 -storetype JKS -keystore keystore.jks -validity 3650
         * key store password = password
         * key password = keypassword
         */
        final String keyPass = null;
        final KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(getClass().getResource("keystore.jks").openStream(),
                      keyPass != null ? keyPass.toCharArray() : null);

        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "keypassword".toCharArray());

        final TrustManagerFactory tmf
                = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        final Server server
                = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                     .tls(kmf, sslContextBuilder -> {
                                         sslContextBuilder.keyManager(kmf);
                                         sslContextBuilder.trustManager(tmf);
                                     })
                                     .build();
    }

    @Test
    void testPkcs12KeyStoreWithNullPassword() throws Exception {
        // Dummy keystore generation
        // keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore keystore.p12 -validity 3650
        // keypassword = keystorepassword = password
        assertThrows(RuntimeException.class, () -> {
            final String keyPass = null;
            final KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(getClass().getResource("keystore.p12").openStream(),
                          keyPass != null ? keyPass.toCharArray() : null);

            final KeyManagerFactory kmf
                    = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, "password".toCharArray());

            final TrustManagerFactory tmf
                    = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            final Server server
                    = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                         .tls(kmf, sslContextBuilder -> {
                                             sslContextBuilder.keyManager(kmf);
                                             sslContextBuilder.trustManager(tmf);
                                         })
                                         .build();
        });
    }
}
