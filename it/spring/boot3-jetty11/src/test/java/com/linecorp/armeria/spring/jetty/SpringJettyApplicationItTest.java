/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.spring.jetty;

import static com.linecorp.armeria.spring.jetty.MatrixVariablesTest.JETTY_BASE_PATH;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerPort;

import jakarta.inject.Inject;

@ActiveProfiles("testbed")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SpringJettyApplicationItTest {
    @Inject
    private ApplicationContext applicationContext;
    @Inject
    private Server server;
    private int httpPort;
    @Inject
    private TestRestTemplate restTemplate;
    @Inject
    private GreetingController greetingController;

    @BeforeEach
    public void init() throws Exception {
        httpPort = server.activePorts()
                         .values()
                         .stream()
                         .filter(ServerPort::hasHttp)
                         .findAny()
                         .get()
                         .localAddress()
                         .getPort();
    }

    @Test
    void contextLoads() {
        assertThat(greetingController).isNotNull();
    }

    @Test
    void greetingShouldReturnDefaultMessage() throws Exception {
        assertThat(restTemplate.getForObject("http://localhost:" + httpPort + JETTY_BASE_PATH + "/greeting",
                                             String.class))
                .contains("Hello, World!");
    }

    @Test
    void greetingShouldReturnUsersMessage() throws Exception {
        assertThat(restTemplate.getForObject("http://localhost:" + httpPort +
                                             JETTY_BASE_PATH + "/greeting?name=Armeria",
                                             String.class))
                .contains("Hello, Armeria!");
    }

    @Test
    void greetingShouldReturn404() throws Exception {
        assertThat(restTemplate.getForEntity("http://localhost:" + httpPort + JETTY_BASE_PATH + "/greet",
                                             Void.class)
                               .getStatusCode().value()).isEqualByComparingTo(404);
    }
}
