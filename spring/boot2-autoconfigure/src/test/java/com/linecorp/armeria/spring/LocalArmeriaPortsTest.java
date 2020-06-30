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
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
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
import com.linecorp.armeria.spring.LocalArmeriaPortsTest.TestConfiguration;

/**
 * Tests for {@link LocalArmeriaPorts}.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
public class LocalArmeriaPortsTest {

    @SpringBootApplication
    @Import(ArmeriaOkServiceConfiguration.class)
    static class TestConfiguration {

        @Bean
        LocalArmeriaPortsForFieldInjection localArmeriaPortsForFieldInjection() {
            return new LocalArmeriaPortsForFieldInjection();
        }

        @Bean
        LocalArmeriaPortsForMethodInjection localArmeriaPortsForMethodInjection() {
            return new LocalArmeriaPortsForMethodInjection();
        }
    }

    static class LocalArmeriaPortsForFieldInjection {

        @LocalArmeriaPorts
        private List<Integer> ports;

        List<Integer> getPorts() {
            return ports;
        }
    }

    static class LocalArmeriaPortsForMethodInjection {

        private List<Integer> ports;

        @LocalArmeriaPorts
        void setPorts(List<Integer> ports) {
            this.ports = ports;
        }

        List<Integer> getPorts() {
            return ports;
        }
    }

    @Inject
    private Server server;
    @Inject
    private BeanFactory beanFactory;
    @LocalArmeriaPorts
    private List<Integer> ports;

    private String newUrl(String scheme, Integer port) {
        return scheme + "://127.0.0.1:" + port;
    }

    @Test
    public void testPortConfigurationFromFieldInjection() throws Exception {
        final Collection<ServerPort> serverPorts = server.activePorts().values();
        final LocalArmeriaPortsForFieldInjection bean = beanFactory.getBean(
                LocalArmeriaPortsForFieldInjection.class);
        final List<Integer> ports = bean.getPorts();
        serverPorts.stream()
                   .map(sp -> sp.localAddress().getPort())
                   .forEach(port -> assertThat(ports).contains(port));
    }

    @Test
    public void testPortConfigurationFromMethodInjection() throws Exception {
        final Collection<ServerPort> serverPorts = server.activePorts().values();
        final LocalArmeriaPortsForMethodInjection bean = beanFactory.getBean(
                LocalArmeriaPortsForMethodInjection.class);
        final List<Integer> ports = bean.getPorts();
        serverPorts.stream()
                   .map(sp -> sp.localAddress().getPort())
                   .forEach(port -> assertThat(ports).contains(port));
    }

    @Test
    public void testHttpServiceRegistrationBean() throws Exception {
        for (Integer port : ports) {
            final WebClient client = WebClient.of(newUrl("h1c", port));
            final HttpResponse response = client.get("/ok");
            final AggregatedHttpResponse res = response.aggregate().get();
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentUtf8()).isEqualTo("ok");
        }
    }
}
