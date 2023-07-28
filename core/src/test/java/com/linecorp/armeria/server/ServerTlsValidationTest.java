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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

class ServerTlsValidationTest {

    private static final String RESOURCE_PATH_PREFIX =
            "/testing/core/" + ServerTlsValidationTest.class.getSimpleName() + '/';

    // TODO: Re-enable this test once we figure out why it does not raise an exception. When key store password
    //       is not given to key store, it is expected that this test raise an exception. For reference, in case
    //       of PKCS12 key store, an exception is raised when key store password is not given.
    @Disabled
    @Test
    void testJksKeyStoreWithNullPassword() throws Exception {
        /*
         * Dummy keystore generation
         * keytool -genkeypair -keyalg RSA -keysize 2048 -storetype JKS -keystore keystore.jks -validity 3650
         * key store password = password
         * key password = keypassword
         */
        final KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(getClass().getResourceAsStream(RESOURCE_PATH_PREFIX + "keystore.jks"), null);

        final KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "keypassword".toCharArray());

        assertThatThrownBy(() -> Server.builder()
                                       .service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                       .tls(kmf)
                                       .build())
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SSLHandshakeException.class)
                .hasMessageContaining("failed to validate SSL/TLS configuration");
    }

    @Test
    void testPkcs12KeyStoreWithNullPassword() throws Exception {
        /*
         * Dummy keystore generation
         * keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore keystore.p12 -validity 3650
         * keypassword = keystorepassword = password
         */
        final KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(getClass().getResourceAsStream(RESOURCE_PATH_PREFIX + "keystore.p12"), null);

        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "password".toCharArray());

        assertThatThrownBy(() -> Server.builder()
                                       .service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                       .tls(kmf)
                                       .build())
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SSLHandshakeException.class)
                .hasMessageContaining("failed to validate SSL/TLS configuration");
    }

    @Test
    void testPkcs12KeyStoreWithPassword() throws Exception {
        /*
         * Dummy keystore generation
         * keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore keystore.p12 -validity 3650
         * keypassword = keystorepassword = password
         */
        final KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(getClass().getResourceAsStream(RESOURCE_PATH_PREFIX + "keystore.p12"),
                      "password".toCharArray());

        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "password".toCharArray());

        Server.builder()
              .service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
              .tls(kmf)
              .build();
    }
}
