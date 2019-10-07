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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

class ServerTlsValidationTest {

    // Note: When key store password is not given to key store, it is expected that an exception occurs when
    //       Server.builder().build() method is called. But when users use JKS key store, the exception is never
    //       raised. (In case of PKCS12 key store, the exception is raised as expected.) Not sure why this
    //       happens and this test needs to be updated when investigation completes.
    @Disabled
    @Test
    void testJksKeyStoreWithNullPassword() {
        /*
         * Dummy keystore generation
         * keytool -genkeypair -keyalg RSA -keysize 2048 -storetype JKS -keystore keystore.jks -validity 3650
         * key store password = password
         * key password = keypassword
         */
        assertThatThrownBy(() -> {
            final KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(getClass().getResource("keystore.jks").openStream(), null);

            final KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, "keypassword".toCharArray());

            final Server server = Server.builder()
                                        .service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                        .tls(kmf, sslContextBuilder -> {})
                                        .build();
        }).isInstanceOf(RuntimeException.class)
          .hasMessageContaining("failed to validate SSL/TLS configuration");
    }

    @Test
    void testPkcs12KeyStoreWithNullPassword() throws Exception {
        /*
         * Dummy keystore generation
         * keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore keystore.p12 -validity 3650
         * keypassword = keystorepassword = password
         */
        assertThatThrownBy(() -> {
            final KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(getClass().getResource("keystore.p12").openStream(), null);

            final KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, "password".toCharArray());

            final Server server = Server.builder()
                                        .service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                        .tls(kmf, sslContextBuilder -> {})
                                        .build();
        }).isInstanceOf(RuntimeException.class)
          .hasMessageContaining("failed to validate SSL/TLS configuration");
    }
}
