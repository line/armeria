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

package com.linecorp.armeria.spring.athenz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.athenz.AthenzClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.server.athenz.AthenzDocker;
import com.linecorp.armeria.server.athenz.AthenzExtension;
import com.linecorp.armeria.spring.LocalArmeriaPort;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ActiveProfiles({ "local", "athenzTest" })
@SpringBootTest(webEnvironment = WebEnvironment.NONE, classes = {
        AthenzSpringBootTest.TestConfiguration.class,
        SpringAthenzMain.class
})
@EnabledIfDockerAvailable
class AthenzSpringBootTest {

    @RegisterExtension
    static AthenzExtension
            athenzDocker = new AthenzExtension(new File("gen-src/test/resources/docker/docker-compose.yml"));

    private static final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private static final List<String> customizerInvocations = new ArrayList<>();

    static class TestConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return meterRegistry;
        }

        @Order(1)
        @Bean
        Consumer<ZtsBaseClientBuilder> ztsBaseClientCustomizer1() {
            return builder -> {
                customizerInvocations.add("ztsBaseClientCustomizer1");
            };
        }

        @Order(0)
        @Bean
        Consumer<ZtsBaseClientBuilder> ztsBaseClientCustomizer0() {
            return builder -> {
                customizerInvocations.add("ztsBaseClientCustomizer0");
            };
        }
    }

    private static ZtsBaseClient ztsBaseClient;

    @BeforeAll
    static void beforeAll() {
        final String tenantKeyFile = "gen-src/test/resources/docker/certs/foo-service/key.pem";
        final String tenantCertFile = "gen-src/test/resources/docker/certs/foo-service/cert.pem";
        final String caCertFile = "gen-src/test/resources/docker/certs/CAs/athenz_ca_cert.pem";
        ztsBaseClient = ZtsBaseClient.builder(athenzDocker.ztsUri())
                                     .keyPair(tenantKeyFile, tenantCertFile)
                                     // caCertFile may not be necessary in production,
                                     // but it is required for testing.
                                     .trustedCertificate(caCertFile)
                                     .build();
    }

    @AfterAll
    static void afterAll() {
        ztsBaseClient.close();
        customizerInvocations.clear();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("armeria.athenz.zts-uri", athenzDocker::ztsUri);
    }

    @LocalArmeriaPort
    private int armeriaPort;

    @Test
    void shouldRejectUnauthorizedRequests() throws InterruptedException {
        final BlockingWebClient client = WebClient.builder("http://127.0.0.1:" + armeriaPort)
                                                  .build()
                                                  .blocking();
        final AggregatedHttpResponse response = client.get("/files/armeria");
        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.headers().get("X-Test-Header")).isEqualTo("TestValue");
    }

    @Test
    void shouldObtainPermission() {
        final BlockingWebClient client =
                WebClient.builder("http://127.0.0.1:" + armeriaPort)
                         .decorator(AthenzClient.newDecorator(ztsBaseClient,
                                                              AthenzDocker.TEST_DOMAIN_NAME,
                                                              AthenzDocker.USER_ROLE,
                                                              TokenType.ACCESS_TOKEN))
                         .build()
                         .blocking();
        final AggregatedHttpResponse response = client.get("/files/armeria");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("file:armeria");
        assertThat(response.headers().get("X-Test-Header")).isEqualTo("TestValue");

        assertThat(customizerInvocations)
                .containsExactly("ztsBaseClientCustomizer0", "ztsBaseClientCustomizer1");
        await().untilAsserted(() -> {
            assertThat(MoreMeters.measureAll(meterRegistry))
                    .anySatisfy((k, v) -> {
                        assertThat(k).startsWith("test.armeria.athenz.zts.client.active.requests");
                    });
        });
    }
}
