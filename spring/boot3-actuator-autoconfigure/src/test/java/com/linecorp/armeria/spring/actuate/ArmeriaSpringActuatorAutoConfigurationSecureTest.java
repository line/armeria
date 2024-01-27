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

import static com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfigurationTest.TEST_LOGGER_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.endpoint.jmx.JmxEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfigurationSecureTest.TestConfiguration;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "secureTest" })
@DirtiesContext
@EnableTestMetrics
@EnableAutoConfiguration
@ImportAutoConfiguration({ ArmeriaSpringActuatorAutoConfiguration.class, JmxEndpointAutoConfiguration.class })
@Timeout(10)
class ArmeriaSpringActuatorAutoConfigurationSecureTest {

    // We use this logger to test the /loggers endpoint, so set the name manually instead of using class name.
    @SuppressWarnings("unused")
    private static final Logger TEST_LOGGER = LoggerFactory.getLogger(TEST_LOGGER_NAME);

    @SpringBootApplication
    static class TestConfiguration {}

    @LocalManagementPort
    private Integer actuatorPort;
    @Inject
    private Server server;
    @Inject
    private ArmeriaSettings settings;

    @Test
    void normal() throws Exception {
        server.activePorts().values().stream()
              .map(p -> p.localAddress().getPort())
              .forEach(port -> {
                  final int statusCode = actuatorPort.equals(port) ? 200 : 404;
                  assertStatus(port, "/actuator", statusCode);
                  assertStatus(port, "/actuator/loggers/" + TEST_LOGGER_NAME, statusCode);
                  assertStatus(port, "/actuator/prometheus", statusCode);

                  // excluded endpoint
                  // ref: https://github.com/spring-projects/spring-boot/blob/e3aac5913ed3caf53b34eb7750138a4ed6839549/spring-boot-project/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/expose/IncludeExcludeEndpointFilter.java#L123-L139
                  assertStatus(port, "/actuator/health", 404);
                  assertStatus(port, "/actuator/info", 404);
                  assertStatus(port, "/actuator/env", 404);
                  assertStatus(port, "/actuator/configprops", 404);
                  assertStatus(port, "/actuator/threaddump", 404);

                  assertStatus(port, settings.getDocsPath(), statusCode);
                  assertStatus(port, settings.getHealthCheckPath(), statusCode);
                  assertStatus(port, settings.getMetricsPath(), statusCode);
              });
    }

    private static void assertStatus(int port, String url, int statusCode) {
        final WebClient client = WebClient.of(newUrl("http", port));
        final HttpResponse response = client.get(url);

        final AggregatedHttpResponse httpResponse = response.aggregate().join();
        assertThat(httpResponse.status().code()).isEqualTo(statusCode);
    }

    private static String newUrl(String scheme, int port) {
        return scheme + "://127.0.0.1:" + port;
    }
}
