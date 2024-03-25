/*
 * Copyright 2023 LINE Corporation
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

import java.net.InetAddress;
import java.util.Collection;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;
import com.linecorp.armeria.spring.LocalhostAddressTest.TestConfiguration;

/**
 * Tests for keeping the behavior of {@link Port#getIp()}.
 */
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "localhostAddressTest" })
@DirtiesContext
class LocalhostAddressTest {

    @SpringBootApplication
    static class TestConfiguration {}

    @Inject
    private Server server;

    @Test
    void testLocalhostAddressCanBeUsed() {
        final Collection<ServerPort> serverPorts = server.activePorts().values();
        assertThat(serverPorts).hasSize(1);
        for (ServerPort sp : serverPorts) {
            final InetAddress address = sp.localAddress().getAddress();
            assertThat(address.isLoopbackAddress()).isTrue();
        }
    }
}
