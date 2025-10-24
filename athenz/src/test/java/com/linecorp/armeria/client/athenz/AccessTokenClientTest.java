/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.athenz;

import static com.linecorp.armeria.server.athenz.AthenzDocker.ATHENZ_CERTS;
import static com.linecorp.armeria.server.athenz.AthenzDocker.CA_CERT_FILE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.USER_ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.server.athenz.AthenzExtension;

@EnabledIfDockerAvailable
class AccessTokenClientTest {

    @RegisterExtension
    static AthenzExtension athenzExtension = new AthenzExtension();

    @Test
    void shouldReturnCachedToken() throws Exception {
        final URI ztsUri = athenzExtension.ztsUri();

        final String serviceKeyFile = ATHENZ_CERTS + "foo-service/key.pem";
        final String serviceCertFile = ATHENZ_CERTS + "foo-service/cert.pem";
        final AtomicReference<TlsKeyPair> keyPairRef = new AtomicReference<>();
        final InputStream serviceKey = AccessTokenClientTest.class.getResourceAsStream(serviceKeyFile);
        final InputStream serviceCert = AccessTokenClientTest.class.getResourceAsStream(serviceCertFile);
        final InputStream caCert = AthenzExtension.class.getResourceAsStream(CA_CERT_FILE);
        keyPairRef.set(TlsKeyPair.of(serviceKey, serviceCert));
        final ZtsBaseClient ztsBaseClient =
                ZtsBaseClient.builder(ztsUri)
                             .keyPair(keyPairRef::get)
                             .trustedCertificate(caCert)
                             .build();

        final AccessTokenClient tokenClient = new AccessTokenClient(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                    ImmutableList.of(USER_ROLE),
                                                                    Duration.ofSeconds(10));
        final String token0 = tokenClient.getToken().join();
        assertThat(token0).isNotEmpty();
        final String token1 = tokenClient.getToken().join();
        // AccessTokenClient caches the token, so the two tokens should be equal.
        assertThat(token1).isEqualTo(token0);
        Thread.sleep(1000);
        final String token2 = tokenClient.getToken().join();
        assertThat(token2).isEqualTo(token1);
    }

    @Test
    void refreshTokenOnKeyPairUpdates() throws Exception {
        final URI ztsUri = athenzExtension.ztsUri();

        final String serviceKeyFile = ATHENZ_CERTS + "foo-service/key.pem";
        final String serviceCertFile = ATHENZ_CERTS + "foo-service/cert.pem";
        final AtomicReference<TlsKeyPair> keyPairRef = new AtomicReference<>();
        final InputStream serviceKey = AccessTokenClientTest.class.getResourceAsStream(serviceKeyFile);
        final InputStream serviceCert = AccessTokenClientTest.class.getResourceAsStream(serviceCertFile);
        final InputStream caCert = AthenzExtension.class.getResourceAsStream(CA_CERT_FILE);
        keyPairRef.set(TlsKeyPair.of(serviceKey, serviceCert));
        final ZtsBaseClient ztsBaseClient =
                ZtsBaseClient.builder(ztsUri)
                             .keyPair(keyPairRef::get)
                             .trustedCertificate(caCert)
                             .build();

        final AccessTokenClient tokenClient = new AccessTokenClient(ztsBaseClient, TEST_DOMAIN_NAME,
                                                                    ImmutableList.of(USER_ROLE),
                                                                    Duration.ofSeconds(10));
        final String token0 = tokenClient.getToken().join();
        assertThat(token0).isNotEmpty();
        final String token1 = tokenClient.getToken().join();
        // AccessTokenClient caches the token, so the two tokens should be equal.
        assertThat(token1).isEqualTo(token0);
        final String newServiceKeyFile = ATHENZ_CERTS + "foo-service-new/key.pem";
        final String newServiceCertFile = ATHENZ_CERTS + "foo-service-new/cert.pem";
        final InputStream newServiceKey = AccessTokenClientTest.class.getResourceAsStream(newServiceKeyFile);
        final InputStream newServiceCert = AccessTokenClientTest.class.getResourceAsStream(newServiceCertFile);
        keyPairRef.set(TlsKeyPair.of(newServiceKey, newServiceCert));
        // After updating the key pair, the token should be refreshed.
        final String token2 = tokenClient.getToken().join();
        assertThat(token2).isEqualTo(token1);
    }
}
