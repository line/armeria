/*
 * Copyright 2026 LY Corporation
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
package com.linecorp.armeria.spring.actuate;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.PortUtil;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.InternalServices;
import com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfigurationSecureTest.TestConfiguration;

import jakarta.inject.Inject;

@SpringBootTest(classes = TestConfiguration.class, properties = "armeria.test.startup-attempts=1")
@ContextConfiguration(loader = RetryableSpringBootContextLoader.class)
@ActiveProfiles({ "local", "basePathSamePortTest" })
@DirtiesContext
@EnableTestMetrics
@EnableAutoConfiguration
@ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
@Timeout(30)
class ArmeriaSpringActuatorAutoConfigurationPortCollisionTest {

    private static final List<Integer> selectedPorts = new ArrayList<>();
    @Nullable
    private static ServerSocket occupiedPort;

    @BeforeAll
    static void setUp() throws IOException {
        selectedPorts.clear();
        occupiedPort = new ServerSocket(0);
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (occupiedPort != null) {
            occupiedPort.close();
        }
    }

    @DynamicPropertySource
    static void registerPortProperties(DynamicPropertyRegistry registry) {
        final ServerSocket socket = requireNonNull(occupiedPort);
        final int port = selectedPorts.isEmpty() ? socket.getLocalPort() : PortUtil.unusedTcpPort();
        selectedPorts.add(port);
        registry.add("armeria.internal-services.port", () -> port);
        registry.add("management.server.port", () -> port);
    }

    @Inject
    private Server server;
    @Inject
    private InternalServices internalServices;
    @LocalManagementPort
    private int managementPort;

    @Test
    void reselectsSharedPortWhileOriginalPortRemainsOccupied() {
        final ServerSocket socket = requireNonNull(occupiedPort);
        assertThat(socket.isClosed()).isFalse();
        assertThat(selectedPorts).hasSizeBetween(2, 8)
                                 .startsWith(socket.getLocalPort())
                                 .endsWith(managementPort);
        assertThat(managementPort).isNotEqualTo(socket.getLocalPort());
        assertThat(internalServices.internalServicePort().getPort()).isEqualTo(managementPort);
        assertThat(internalServices.managementServerPort().getPort()).isEqualTo(managementPort);
        assertThat(server.activePorts().values())
                .anyMatch(port -> port.localAddress().getPort() == managementPort);

        final WebClient client = WebClient.of("http://127.0.0.1:" + managementPort);
        assertThat(client.blocking().get("/foo/actuator/health").status()).isEqualTo(HttpStatus.OK);
        assertThat(client.blocking().get("/internal/healthcheck").status()).isEqualTo(HttpStatus.OK);
        assertThat(client.blocking().get("/actuator/health").status()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
