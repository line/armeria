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

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaAutoConfigurationInternalServiceTest.TestConfiguration;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class,
        properties = "management.metrics.export.defaults.enabled=true")
@ActiveProfiles({ "local", "internalServiceTest" })
@DirtiesContext
public class ArmeriaAutoConfigurationInternalServiceTest {

    @SpringBootApplication
    public static class TestConfiguration {}

    @Inject
    private Server server;
    @Inject
    private ArmeriaSettings settings;
    @Inject
    InternalServices internalServices;

    @Test
    public void exposeInternalServicesToInternalPort() throws Exception {
        final Port internalServicePort = internalServices.internalServicePort();
        assertThat(internalServicePort).isNotNull();
        assertThat(internalServicePort.getProtocols()).containsExactly(SessionProtocol.HTTP);

        final Port managementServerPort = internalServices.managementServerPort();
        assertThat(managementServerPort).isNotNull();
        assertThat(server.activePorts().values())
                .noneMatch(port -> managementServerPort.getPort() == port.localAddress().getPort());

        assertThat(settings.getInternalServices().getInclude()).containsExactly(InternalServiceId.METRICS,
                                                                                InternalServiceId.HEALTH);
        server.activePorts().values().stream()
              .map(p -> p.localAddress().getPort())
              .forEach(port -> {
                  final int internalServiceStatus;
                  if (internalServicePort.getPort() == port) {
                      internalServiceStatus = 200;
                  } else {
                      internalServiceStatus = 404;
                  }

                  assertStatus(port, settings.getHealthCheckPath(), internalServiceStatus);
                  assertStatus(port, settings.getMetricsPath(), internalServiceStatus);

                  // DocService was not included to internal services.
                  // Therefore, all ports could access DocService.
                  assertStatus(port, settings.getDocsPath(), 200);
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
