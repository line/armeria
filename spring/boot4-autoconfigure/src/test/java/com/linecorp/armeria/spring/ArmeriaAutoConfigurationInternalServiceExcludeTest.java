/*
 * Copyright 2026 LINE Corporation
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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationInternalServiceExcludeTest.TestConfiguration;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

import jakarta.inject.Inject;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "internalServiceExcludeTest" })
@DirtiesContext
class ArmeriaAutoConfigurationInternalServiceExcludeTest {

    @SpringBootApplication
    public static class TestConfiguration {}

    @Inject
    private Server server;
    @Inject
    private ArmeriaSettings settings;
    @Inject
    InternalServices internalServices;

    @Test
    void exposeExcludedInternalServicesToAllPorts() {
        final Port internalServicePort = internalServices.internalServicePort();
        assertThat(internalServicePort).isNotNull();
        assertThat(settings.getInternalServices().getExclude()).containsExactly(InternalServiceId.HEALTH);

        assertThat(server.activePorts()).hasSize(2);
        server.activePorts().values().stream()
              .map(p -> p.localAddress().getPort())
              .forEach(port -> {
                  final int internalServiceStatus = internalServicePort.getPort() == port ? 200 : 404;
                  assertStatus(port, settings.getDocsPath(), internalServiceStatus);
                  assertStatus(port, settings.getMetricsPath(), internalServiceStatus);
                  assertStatus(port, settings.getHealthCheckPath(), 200);
              });
    }

    private static void assertStatus(int port, String url, int statusCode) {
        final AggregatedHttpResponse res = WebClient.of("http://127.0.0.1:" + port).blocking().get(url);
        assertThat(res.status().code()).isEqualTo(statusCode);
    }
}
