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
package com.linecorp.armeria.spring.actuate;

import static com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfigurationTest.TEST_LOGGER_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.PortUtil;
import com.linecorp.armeria.internal.testing.FlakyTest;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;
import com.linecorp.armeria.spring.InternalServiceId;
import com.linecorp.armeria.spring.InternalServices;
import com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfigurationSecureTest.TestConfiguration;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "internalServiceTest" })
@DirtiesContext
@AutoConfigureMetrics
@EnableAutoConfiguration
@ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
@Timeout(30)
class ArmeriaSpringActuatorAutoConfigurationInternalServiceTest {

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
    @Inject
    private InternalServices internalServices;

    @Test
    void exposeInternalServicesToManagementServerPort() throws Exception {
        final Port internalServicePort = internalServices.internalServicePort();
        assertThat(internalServicePort).isNotNull();
        assertThat(internalServicePort.getProtocols()).containsExactly(SessionProtocol.HTTP);
        assertThat(internalServices.managementServerPort().getPort()).isEqualTo(actuatorPort);

        assertThat(settings.getInternalServices().getInclude()).containsExactly(InternalServiceId.METRICS,
                                                                                InternalServiceId.HEALTH);

        server.activePorts().values().stream()
              .map(p -> p.localAddress().getPort())
              .forEach(port -> {
                  final int actuatorStatus;
                  final int internalServiceStatus;
                  if (actuatorPort.equals(port)) {
                      actuatorStatus = 200;
                      // Internal services will be exposed to the managment server port.
                      internalServiceStatus = 200;
                  } else if (internalServicePort.getPort() == port) {
                      actuatorStatus = 404;
                      internalServiceStatus = 200;
                  } else {
                      actuatorStatus = 404;
                      internalServiceStatus = 404;
                  }
                  assertActuatorStatus(port, actuatorStatus);
                  assertInternalServiceStatus(port, internalServiceStatus, settings, false);
              });
    }

    @SpringBootTest(classes = TestConfiguration.class)
    @ActiveProfiles({ "local", "actuatorTest" })
    @DirtiesContext
    @AutoConfigureMetrics
    @EnableAutoConfiguration
    @ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
    @Timeout(30)
    static class ActuatorTest {
        @LocalManagementPort
        private Integer actuatorPort;
        @Inject
        private Server server;
        @Inject
        private ArmeriaSettings settings;
        @Inject
        private InternalServices internalServices;

        @Test
        void exposeActuatorServiceToInternalServicePort() throws Exception {
            final Port internalServicePort = internalServices.internalServicePort();
            assertThat(internalServicePort).isNotNull();
            assertThat(internalServicePort.getProtocols()).containsExactly(SessionProtocol.HTTP);
            assertThat(internalServicePort.getPort()).isNotEqualTo(actuatorPort);
            assertThat(settings.getInternalServices().getInclude()).containsExactly(InternalServiceId.METRICS,
                                                                                    InternalServiceId.HEALTH,
                                                                                    InternalServiceId.ACTUATOR);
            assertThat(internalServices.managementServerPort().getPort()).isEqualTo(actuatorPort);

            server.activePorts().values().stream()
                  .map(p -> p.localAddress().getPort())
                  .forEach(port -> {
                      final int actuatorStatus;
                      final int internalServiceStatus;
                      if (actuatorPort.equals(port) || internalServicePort.getPort() == port) {
                          actuatorStatus = 200;
                          internalServiceStatus = 200;
                      } else {
                          actuatorStatus = 404;
                          internalServiceStatus = 404;
                      }
                      assertActuatorStatus(port, actuatorStatus);
                      assertInternalServiceStatus(port, internalServiceStatus, settings, false);
                  });
        }
    }

    @SpringBootTest(classes = TestConfiguration.class)
    @ActiveProfiles({ "local", "basePathTest" })
    @DirtiesContext
    @AutoConfigureMetrics
    @EnableAutoConfiguration
    @ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
    @Timeout(30)
    static class BasePathTest {
        @LocalManagementPort
        private Integer actuatorPort;
        @Inject
        private ArmeriaSettings settings;
        @Inject
        private InternalServices internalServices;

        @Test
        void exposeActuatorServiceHasCustomPrefix() throws Exception {
            final Port internalServicePort = internalServices.internalServicePort();
            assertThat(internalServicePort).isNotNull();
            assertThat(internalServicePort.getProtocols()).containsExactly(SessionProtocol.HTTP);
            assertThat(internalServicePort.getPort()).isNotEqualTo(actuatorPort);
            assertThat(settings.getInternalServices().getInclude()).containsExactly(InternalServiceId.METRICS,
                                                                                    InternalServiceId.HEALTH,
                                                                                    InternalServiceId.ACTUATOR);
            assertThat(internalServices.managementServerPort().getPort()).isEqualTo(actuatorPort);

            assertActuatorStatus(actuatorPort, 200, "/foo");
            assertInternalServiceStatus(actuatorPort, 200, settings, false);
            assertActuatorStatus(internalServicePort.getPort(), 200);
            assertInternalServiceStatus(internalServicePort.getPort(), 200, settings, false);
            assertActuatorStatus(internalServicePort.getPort(), 404, "/foo");
        }
    }

    @SpringBootTest(classes = TestConfiguration.class)
    @ActiveProfiles({ "local", "basePathSamePortTest" })
    @DirtiesContext
    @AutoConfigureMetrics
    @EnableAutoConfiguration
    @ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
    @Timeout(30)
    @FlakyTest
    static class BasePathSamePortTest {
        @LocalManagementPort
        private Integer actuatorPort;
        @Inject
        private ArmeriaSettings settings;
        @Inject
        private InternalServices internalServices;

        @DynamicPropertySource
        static void registerPortProperties(DynamicPropertyRegistry registry) {
            final int port = PortUtil.unusedTcpPort();
            registry.add("armeria.internal-services.port", () -> port);
            registry.add("management.server.port", () -> port);
        }

        @Test
        void exposeActuatorServiceHasSingleCustomPrefixAtInternalPort() throws Exception {
            final Port internalServicePortSetting = internalServices.internalServicePort();
            assertThat(internalServicePortSetting).isNotNull();
            assertThat(internalServicePortSetting.getProtocols()).containsExactly(SessionProtocol.HTTP);

            assertThat(settings.getInternalServices().getInclude()).containsExactly(InternalServiceId.METRICS,
                                                                                    InternalServiceId.HEALTH,
                                                                                    InternalServiceId.ACTUATOR);

            final int internalServicesPort = internalServicePortSetting.getPort();
            assertActuatorStatus(internalServicesPort, 200, "/foo");
            assertInternalServiceStatus(internalServicesPort, 200, settings, false);
            assertActuatorStatus(internalServicesPort, 404);

            assertThat(internalServicesPort).isEqualTo(actuatorPort);
            assertThat(internalServices.managementServerPort().getPort()).isEqualTo(actuatorPort);
        }
    }

    @SpringBootTest(classes = TestConfiguration.class)
    @ActiveProfiles({ "local", "basePathWithoutPortTest" })
    @DirtiesContext
    @AutoConfigureMetrics
    @EnableAutoConfiguration
    @ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
    @Timeout(30)
    static class BasePathWithoutPortTest {
        @Inject
        private Server server;
        @Inject
        private ArmeriaSettings settings;
        @Inject
        private InternalServices internalServices;

        @Test
        void exposeActuatorServiceIgnoreBasePathSettingWithoutPort() throws Exception {
            final Port internalServicePort = internalServices.internalServicePort();
            assertThat(internalServicePort).isNotNull();
            assertThat(internalServicePort.getProtocols()).containsExactly(SessionProtocol.HTTP);
            assertThat(settings.getInternalServices().getInclude()).containsExactly(InternalServiceId.METRICS,
                                                                                    InternalServiceId.HEALTH,
                                                                                    InternalServiceId.ACTUATOR);
            assertThat(internalServices.managementServerPort()).isNull();

            server.activePorts().values().stream()
                  .map(p -> p.localAddress().getPort())
                  .forEach(port -> {
                      final int actuatorStatus;
                      final int internalServiceStatus;
                      if (internalServicePort.getPort() == port) {
                          actuatorStatus = 200;
                          internalServiceStatus = 200;
                      } else {
                          actuatorStatus = 404;
                          internalServiceStatus = 404;
                      }
                      assertActuatorStatus(port, actuatorStatus);
                      assertInternalServiceStatus(port, internalServiceStatus, settings, false);
                  });
        }
    }

    @SpringBootTest(classes = TestConfiguration.class)
    @ActiveProfiles({ "local", "allInternalServices" })
    @DirtiesContext
    @AutoConfigureMetrics
    @EnableAutoConfiguration
    @ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
    @Timeout(30)
    static class AllInternalServicesTest {
        @Inject
        private Server server;
        @Inject
        private ArmeriaSettings settings;
        @Inject
        private InternalServices internalServices;

        @Test
        void exposeAllInternalServicesToInternalServicePort() throws Exception {
            final Port internalServicePort = internalServices.internalServicePort();
            assertThat(internalServicePort).isNotNull();
            assertThat(internalServicePort.getProtocols()).containsExactly(SessionProtocol.HTTP);
            assertThat(settings.getInternalServices().getInclude()).containsExactly(InternalServiceId.ALL);
            assertThat(internalServices.managementServerPort()).isNull();

            server.activePorts().values().stream()
                  .map(p -> p.localAddress().getPort())
                  .forEach(port -> {
                      final int actuatorStatus;
                      final int internalServiceStatus;
                      if (internalServicePort.getPort() == port) {
                          actuatorStatus = 200;
                          internalServiceStatus = 200;
                      } else {
                          actuatorStatus = 404;
                          internalServiceStatus = 404;
                      }
                      assertActuatorStatus(port, actuatorStatus);
                      assertInternalServiceStatus(port, internalServiceStatus, settings, true);
                  });
        }
    }

    @SpringBootTest(classes = TestConfiguration.class)
    @ActiveProfiles({ "local", "defaultInternalServices" })
    @DirtiesContext
    @AutoConfigureMetrics
    @EnableAutoConfiguration
    @ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
    @Timeout(30)
    static class DefaultInternalServicesTest {
        @Inject
        private Server server;
        @Inject
        private ArmeriaSettings settings;
        @Inject
        private InternalServices internalServices;

        @Test
        void exposeDefaultInternalServicesToInternalServicePort() throws Exception {
            final Port internalServicePort = internalServices.internalServicePort();
            assertThat(internalServicePort).isNotNull();
            assertThat(internalServicePort.getProtocols()).containsExactly(SessionProtocol.HTTP);
            assertThat(settings.getInternalServices().getInclude()).containsExactly(InternalServiceId.DOCS,
                                                                                    InternalServiceId.HEALTH,
                                                                                    InternalServiceId.METRICS,
                                                                                    InternalServiceId.ACTUATOR);
            assertThat(internalServices.managementServerPort()).isNull();

            server.activePorts().values().stream()
                  .map(p -> p.localAddress().getPort())
                  .forEach(port -> {
                      final int actuatorStatus;
                      final int internalServiceStatus;
                      if (internalServicePort.getPort() == port) {
                          actuatorStatus = 200;
                          internalServiceStatus = 200;
                      } else {
                          actuatorStatus = 404;
                          internalServiceStatus = 404;
                      }
                      assertActuatorStatus(port, actuatorStatus);
                      assertInternalServiceStatus(port, internalServiceStatus, settings, true);
                  });
        }
    }

    @SpringBootTest(classes = TestConfiguration.class)
    @ActiveProfiles({ "local", "managementLocalhostTest" })
    @DirtiesContext
    @AutoConfigureMetrics
    @EnableAutoConfiguration
    @ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
    @Timeout(30)
    static class ManagementLocalhostTest {
        @LocalManagementPort
        private Integer actuatorPort;
        @Inject
        private ArmeriaSettings settings;
        @Inject
        private InternalServices internalServices;

        @Test
        void bindToLocalhost() throws Exception {
            final Port internalServicePort = internalServices.internalServicePort();
            assertThat(internalServicePort).isNotNull();
            assertThat(internalServicePort.getProtocols()).containsExactly(SessionProtocol.HTTP);
            assertThat(internalServicePort.getPort()).isNotEqualTo(actuatorPort);
            assertThat(settings.getInternalServices().getInclude()).containsExactly(InternalServiceId.METRICS,
                                                                                    InternalServiceId.HEALTH,
                                                                                    InternalServiceId.ACTUATOR);
            assertThat(internalServices.managementServerPort().getPort()).isEqualTo(actuatorPort);

            assertActuatorStatus(actuatorPort, 200);
            assertInternalServiceStatus(actuatorPort, 200, settings, false);
            assertActuatorStatus(internalServicePort.getPort(), 200);
            assertInternalServiceStatus(internalServicePort.getPort(), 200, settings, false);
        }
    }

    private static void assertActuatorStatus(int port, int actuatorStatus) {
        assertActuatorStatus(port, actuatorStatus, "");
    }

    private static void assertActuatorStatus(int port, int actuatorStatus, String basePath) {
        assertStatus(port, basePath + "/actuator", actuatorStatus);
        assertStatus(port, basePath + "/actuator/health", actuatorStatus);
        assertStatus(port, basePath + "/actuator/loggers/" + TEST_LOGGER_NAME, actuatorStatus);
        assertStatus(port, basePath + "/actuator/prometheus", actuatorStatus);
    }

    private static void assertInternalServiceStatus(int port, int internalServiceStatus,
                                                    ArmeriaSettings settings,
                                                    boolean docsServiceIncludedToInternalService) {
        assertStatus(port, settings.getHealthCheckPath(), internalServiceStatus);
        assertStatus(port, settings.getMetricsPath(), internalServiceStatus);

        // DocService was not included to internal services.
        // Therefore, all ports could access DocService.
        assertStatus(port, settings.getDocsPath(), docsServiceIncludedToInternalService ?
                                                   internalServiceStatus : 200);
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
