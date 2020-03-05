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

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

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
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "secureTest" })
public class ArmeriaAutoConfigurationWithSecureTest {

    @SpringBootApplication
    static class TestConfiguration {
        @Bean
        public Consumer<ServerBuilder> customizer() {
            return sb -> sb.service("/customizer", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    }

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Test
    public void normal() throws Exception {
        assertStatus(8080, "/customizer", 200);
        assertStatus(8081, "/customizer", 200);
        assertStatus(8080, "/internal/healthcheck", 404);
        assertStatus(8081, "/internal/healthcheck", 200);
        assertStatus(8080, "/internal/metrics", 404);
        assertStatus(8081, "/internal/metrics", 200);
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
