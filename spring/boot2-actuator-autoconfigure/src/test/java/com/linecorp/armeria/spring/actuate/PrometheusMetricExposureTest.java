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

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.web.server.LocalManagementPort;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;

@SpringBootTest(classes = org.springframework.boot.test.context.TestConfiguration.class)
@ActiveProfiles({ "local", "managedMetricPath" })
@DirtiesContext
@EnableAutoConfiguration
@ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
class PrometheusMetricExposureTest {

    @SpringBootApplication
    class TestConfiguration {}

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
        AggregatedHttpResponse res = client.get("/internal/actuator/prometheus").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
        res = managementClient.get("/internal/actuator/prometheus").aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        // Make sure that the exposed results contain the metrics collected by MetricCollectingService
        assertThat(res.contentAscii()).contains("armeria_server_response_duration_seconds");
    }
}
