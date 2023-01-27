/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.spring.actuate;

import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.actuate.autoconfigure.web.server.LocalManagementPort;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfigurationSecureTest.TestConfiguration;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "ssl" })
@DirtiesContext
@AutoConfigureMetrics
@EnableAutoConfiguration
@ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
@Timeout(10)
class ArmeriaSpringActuatorAutoConfigurationSslTest {
    private static final X509Certificate[] EMPTY_CERTIFICATES = new X509Certificate[0];

    @SpringBootApplication
    static class TestConfiguration {}

    @LocalManagementPort
    private Integer actuatorPort;

    @Test
    void usingSsl() {
        final AtomicReference<String> actualKeyNameOfManagementServer = new AtomicReference<>();

        // Create a new ClientFactory with a TrustManager that records the received certificate.
        try (ClientFactory clientFactory = ClientFactory.builder()
                                                        .tlsNoVerify()
                                                        .build()) {

            // Send a request to make the TrustManager record the certificate.
            final WebClient client = WebClient.builder("https://127.0.0.1:" + actuatorPort)
                                              .factory(clientFactory)
                                              .build();
            client.get("/").aggregate().join();
        }
    }
}
