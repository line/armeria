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

package com.linecorp.armeria.spring.tomcat;

import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import com.linecorp.armeria.internal.server.tomcat.TomcatVersion;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerPort;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SpringTomcatApplicationItTest {
    @Inject
    private ApplicationContext applicationContext;
    @Inject
    private Server server;
    private int httpPort;
    @Inject
    private TestRestTemplate restTemplate;
    @Inject
    private GreetingController greetingController;
    @Value("${armeria-tomcat.version.major:9}")
    private int tomcatMajorVersion;
    @Value("${armeria-tomcat.version.minor:0}")
    private int tomcatMinorVersion;

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
    void verifyTomcatVersion() {
        assertThat(TomcatVersion.major()).isEqualTo(tomcatMajorVersion);
        assertThat(TomcatVersion.minor()).isEqualTo(tomcatMinorVersion);
    }

    @Test
    void verifySingleConnector() {
        // Relevant to Tomcat 9.0
        assertThat(applicationContext).isInstanceOf(WebServerApplicationContext.class);
        final WebServer webServer = ((WebServerApplicationContext) applicationContext).getWebServer();
        assertThat(webServer).isInstanceOf(TomcatWebServer.class);
        assertThat(((TomcatWebServer) webServer).getTomcat()
                                                .getEngine()
                                                .getService()
                                                .findConnectors()).hasSize(1);
    }

    @Test
    void greetingShouldReturnDefaultMessage() throws Exception {
        assertThat(restTemplate.getForObject("http://localhost:" +
                                             httpPort +
                                             "/tomcat/api/rest/v1/greeting",
                                             String.class))
                .contains("Hello, World!");
    }

    @Test
    void greetingShouldReturnUsersMessage() throws Exception {
        assertThat(restTemplate.getForObject("http://localhost:" +
                                             httpPort +
                                             "/tomcat/api/rest/v1/greeting?name=Armeria",
                                             String.class))
                .contains("Hello, Armeria!");
    }

    @Test
    void greetingShouldReturn404() throws Exception {
        assertThat(restTemplate.getForEntity("http://localhost:" +
                                             httpPort +
                                             "/tomcat/api/rest/v1/greet",
                                             Void.class)
                               .getStatusCode()).isEqualByComparingTo(HttpStatus.NOT_FOUND);
    }
}
