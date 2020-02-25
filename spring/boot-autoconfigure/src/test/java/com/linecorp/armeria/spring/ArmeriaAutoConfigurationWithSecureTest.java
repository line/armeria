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
package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationWithConsumerTest.TestConfiguration;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * application-secureTest.yml will be loaded with minimal settings to make it work.
 */
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "secureTest" })
@Timeout(10)
class ArmeriaAutoConfigurationWithSecureTest {

    @SpringBootApplication
    static class TestConfiguration {
        @Bean
        public Consumer<ServerBuilder> customizer() {
            return sb -> sb.service("/customizer", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "8080, /customizer, 200",
            "8081, /customizer, 200",
            "8080, /internal/healthcheck, 404",
            "8081, /internal/healthcheck, 200",
            "8080, /internal/metrics, 404",
            "8081, /internal/metrics, 200"
    })
    void normal(Integer port, String path, Integer statusCode) throws Exception {
        assertStatus(port, path, statusCode);
    }

    private static void assertStatus(Integer port, String url, Integer statusCode) throws Exception {
        final WebClient client = WebClient.of(newUrl("http", port));

        final HttpResponse response = client.get(url);

        final AggregatedHttpResponse msg = response.aggregate().get();
        assertThat(msg.status().code()).isEqualTo(statusCode);
    }

    private static String newUrl(String scheme, int port) {
        return scheme + "://127.0.0.1:" + port;
    }
}
