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

import java.util.Collection;
import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.spring.LocalArmeriaPortTest.TestConfiguration;

/**
 * Tests for {@link LocalArmeriaPorts @LocalArmeriaPorts}.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
public class LocalArmeriaPortsTest {

    @SpringBootApplication
    @Import(ArmeriaOkServiceConfiguration.class)
    static class TestConfiguration {
    }

    @LocalArmeriaPorts
    private List<Integer> ports;

    @Inject
    private Server server;

    private String newUrl(String scheme, Integer port) {
        return scheme + "://127.0.0.1:" + port;
    }

    @Test
    public void testPortConfiguration() throws Exception {
        final Collection<ServerPort> serverPorts = server.activePorts().values();
        assertThat(serverPorts).size().isEqualTo(ports.size());
        serverPorts.stream()
                   .map(sp -> sp.localAddress().getPort())
                   .forEach(port -> assertThat(ports).contains(port));
    }

    @Test
    public void testHttpServiceRegistrationBean() {
        ports.forEach(port -> {
            try {
                final WebClient client = WebClient.of(newUrl("h1c", port));

                final HttpResponse response = client.get("/ok");

                final AggregatedHttpResponse res = response.aggregate().get();
                assertThat(res.status()).isEqualTo(HttpStatus.OK);
                assertThat(res.contentUtf8()).isEqualTo("ok");
            } catch (Exception e) {
                // Ignored
            }
        });
    }
}
