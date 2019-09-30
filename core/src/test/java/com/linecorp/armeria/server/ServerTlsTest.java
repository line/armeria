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

import java.io.File;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

import io.netty.handler.ssl.util.SelfSignedCertificate;

class ServerTlsTest {

    static final SelfSignedCertificate ssc;

    static {
        try {
            ssc = new SelfSignedCertificate("a.com");
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSLv2Hello", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"})
    void testTlsCustomizerValidProtocols(String protocol) throws SSLException {
        Server server
                = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                     .tls(ssc.certificate(), ssc.privateKey(), sslContextBuilder -> {
                                         sslContextBuilder.protocols(protocol);
                                     })
                                     .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSLv2", "SSLv2", "SSLv3", "WrongProtocolName"})
    void testTlsCustomizerInvalidProtocols(String protocol) {
        assertThrows(RuntimeException.class, () -> {
            Server server
                    = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                         .tls(ssc.certificate(), ssc.privateKey(), sslContextBuilder -> {
                                             sslContextBuilder.protocols(protocol);
                                         })
                                         .build();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "WrongPassword"})
    void testTlsWithInvalidKeyPassword(String password) throws CertificateException, SSLException {
        assertThrows(RuntimeException.class, () -> {
            Server server
                    = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                         .tls(ssc.certificate(), ssc.privateKey(), password)
                                         .build();
        });
    }

    @Test
    void testTlsWithNeverInitializedKeyMangerFactory() {
        assertThrows(RuntimeException.class, () -> {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            File file = new File(getClass().getResource("keystore.jks").toURI());
            keyStore.load(file.toURI().toURL().openStream(), "4t[9Pxc".toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

            TrustManagerFactory tmf
                    = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            Server server
                    = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                         .tls(kmf, sslContextBuilder -> {
                                             sslContextBuilder.keyManager(kmf);
                                             sslContextBuilder.trustManager(tmf);
                                         })
                                         .build();
        });
    }

    @Test
    void testTlsWithNeverInitializedTrustMangerFactory() {
        assertThrows(RuntimeException.class, () -> {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            File file = new File(getClass().getResource("keystore.jks").toURI());
            keyStore.load(file.toURI().toURL().openStream(), "4t[9Pxc".toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, "B3g9s%ds".toCharArray());

            TrustManagerFactory tmf
                    = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

            Server server
                    = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                         .tls(kmf, sslContextBuilder -> {
                                             sslContextBuilder.keyManager(kmf);
                                             sslContextBuilder.trustManager(tmf);
                                         })
                                         .build();
        });
    }

    @Test
    void testTlsWrongCipherSuites() throws CertificateException, SSLException {
        List<String> ciphers = Arrays.asList(
                "WrongCipherName1",
                "WrongCipherName2"
        );

        assertThrows(RuntimeException.class, () -> {
            Server server
                    = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of("Hello world"))
                                         .tls(ssc.certificate(), ssc.privateKey(), sslContextBuilder -> {
                                             sslContextBuilder.ciphers(ciphers);
                                         })
                                         .build();
        });
    }

    @Test
    void testJksKeyStoreWithNullPassword() throws Exception {
        //keytool -genkeypair -keyalg RSA -keysize 2048 -storetype JKS -keystore keystore.jks -validity 3650
        //key-store-password=password
        //key-password=keypassword
        String keyPass = null;
        KeyStore keyStore = KeyStore.getInstance("JKS");
        File file = new File(getClass().getResource("keystore.jks").toURI());
        keyStore.load(file.toURI().toURL().openStream(), keyPass != null ? keyPass.toCharArray() : null);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "keypassword".toCharArray());

        TrustManagerFactory tmf
                = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        Server server
                = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                     .tls(kmf, sslContextBuilder -> {
                                         sslContextBuilder.keyManager(kmf);
                                         sslContextBuilder.trustManager(tmf);
                                     })
                                     .build();
    }

    @Test
    void testPkcs12KeyStoreWithNullPassword() throws Exception {
        //keytool -genkeypair -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore keystore.p12 -validity 3650
        //keystorepassword=password
        String keyPass = null;
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        File file = new File(getClass().getResource("keystore.p12").toURI());
        keyStore.load(file.toURI().toURL().openStream(), keyPass != null ? keyPass.toCharArray() : null);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "password".toCharArray());

        TrustManagerFactory tmf
                = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        Server server
                = new ServerBuilder().service("/", (ctx, res) -> HttpResponse.of(HttpStatus.OK))
                                     .tls(kmf, sslContextBuilder -> {
                                         sslContextBuilder.keyManager(kmf);
                                         sslContextBuilder.trustManager(tmf);
                                     })
                                     .build();
    }
}
