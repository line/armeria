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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.actuate.PrometheusMetricExposureTest.TestConfiguration;

import io.prometheus.client.exporter.common.TextFormat;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "managedMetricPath" })
@DirtiesContext
@EnableTestMetrics
@EnableAutoConfiguration
@ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
class PrometheusMetricExposureTest {

    @SpringBootApplication
    static class TestConfiguration {}

    @Inject
    private Server server;

    @LocalManagementPort
    private int managementPort;

    private WebClient client;
    private WebClient managementClient;

    @BeforeEach
    void setUp() {
        client = WebClient.of("h2c://127.0.0.1:" + server.activeLocalPort());
        managementClient = WebClient.of("http://127.0.0.1:" + managementPort);
    }

    @Test
    void exposeMetricCollectingServiceOnManagementPort() throws Exception {
        final AggregatedHttpResponse res404 =
                client.get("/internal/actuator/prometheus").aggregate().get();
        assertThat(res404.status()).isEqualTo(HttpStatus.NOT_FOUND);

        // Make sure that the exposed results contain the metrics collected by MetricCollectingService
        await().untilAsserted(() -> {
            final AggregatedHttpResponse res =
                    managementClient.get("/internal/actuator/prometheus").aggregate().get();
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentType()).isEqualTo(MediaType.parse(TextFormat.CONTENT_TYPE_004));
            assertThat(res.contentAscii()).contains("armeria_server_response_duration_seconds");
        });
    }
}
