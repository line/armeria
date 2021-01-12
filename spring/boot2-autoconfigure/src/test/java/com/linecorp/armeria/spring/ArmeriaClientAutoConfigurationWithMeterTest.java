/*
 * Copyright 2021 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.inject.Inject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.ArmeriaClientAutoConfigurationWithMeterTest.TestConfiguration;

/**
 * This uses {@link ArmeriaAutoConfiguration} for integration tests.
 * application-autoConfTest.yml will be loaded with minimal settings to make it work.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class, properties =
        "management.metrics.export.defaults.enabled=true") // @AutoConfigureMetrics is not allowed for boot1.
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
public class ArmeriaClientAutoConfigurationWithMeterTest {

    @SpringBootApplication
    public static class TestConfiguration {
        @Bean
        public Consumer<ServerBuilder> customizer() {
            return sb -> sb.service("/customizer", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    }

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @LocalArmeriaPort
    private Integer port;

    @Inject
    private WebClientBuilder webClientBuilder;

    private String newUrl(String scheme) {
        return scheme + "://127.0.0.1:" + port;
    }

    @Test
    public void test() {
        await().untilAsserted(() -> {
            final WebClient webClient = webClientBuilder.build();
            final String metricReport = webClient.get(newUrl("h1c") + "/internal/metrics")
                                                 .aggregate().join()
                                                 .contentUtf8();
            assertThat(metricReport).contains("# TYPE armeria_client_connections_lifespan_seconds_max");
        });
    }
}
