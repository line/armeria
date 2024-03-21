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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;

import io.netty.handler.ssl.util.SimpleTrustManagerFactory;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class AbstractReactiveWebServerCustomKeyAliasTest {

    static final X509Certificate[] EMPTY_CERTIFICATES = new X509Certificate[0];

    @LocalServerPort
    int port;

    private final String expectedKeyName;

    AbstractReactiveWebServerCustomKeyAliasTest(String expectedKeyName) {
        this.expectedKeyName = expectedKeyName;
    }

    /**
     * Makes sure the specified certificate is selected.
     */
    @Test
    void test() throws Exception {
        final AtomicReference<String> actualKeyName = new AtomicReference<>();

        // Create a new ClientFactory with a TrustManager that records the received certificate.
        try (ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .tlsCustomizer(b -> {
                                      b.trustManager(new TrustManagerFactoryImpl(actualKeyName));
                                  })
                                  .build()) {

            // Send a request to make the TrustManager record the certificate.
            final WebClient client = WebClient.builder("h2://127.0.0.1:" + port)
                                              .factory(clientFactory)
                                              .build();
            client.get("/").aggregate().join();

            assertThat(actualKeyName).hasValue(expectedKeyName);
        }
    }

    private static class TrustManagerFactoryImpl extends SimpleTrustManagerFactory {
        private final AtomicReference<String> actualKeyName;

        TrustManagerFactoryImpl(AtomicReference<String> actualKeyName) {
            this.actualKeyName = actualKeyName;
        }

        @Override
        protected void engineInit(KeyStore keyStore) {}

        @Override
        protected void engineInit(ManagerFactoryParameters managerFactoryParameters) {}

        @Override
        protected TrustManager[] engineGetTrustManagers() {
            return new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            actualKeyName.set(chain[0].getSubjectX500Principal().getName());
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return EMPTY_CERTIFICATES;
                        }
                    }
            };
        }
    }
}
