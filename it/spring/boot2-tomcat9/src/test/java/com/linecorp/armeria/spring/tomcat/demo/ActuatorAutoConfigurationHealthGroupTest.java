/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.spring.tomcat.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.web.server.LocalManagementPort;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.spring.LocalArmeriaPort;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles({ "local", "healthGroupTest" })
@DirtiesContext
@AutoConfigureMetrics
@EnableAutoConfiguration
class ActuatorAutoConfigurationHealthGroupTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    @LocalManagementPort
    private int managementPort;
    @LocalArmeriaPort
    private int armeriaPort;

    private WebClient managementClient;
    private WebClient armeriaClient;

    @BeforeEach
    void setUp() {
        managementClient = WebClient.builder("http://127.0.0.1:" + managementPort).build();
        armeriaClient = WebClient.builder("http://127.0.0.1:" + armeriaPort).build();
    }

    @Test
    void testHealth() throws Exception {
        final AggregatedHttpResponse res = managementClient.get("/internal/actuator/health").aggregate().join();
        assertUpStatus(res);
    }

    private static void assertUpStatus(AggregatedHttpResponse res) throws IOException {
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType().toString()).isEqualTo("application/vnd.spring-boot.actuator.v3+json");

        final Map<String, Object> values = OBJECT_MAPPER.readValue(res.content().array(), JSON_MAP);
        assertThat(values).containsEntry("status", "UP");
    }

    @Test
    void foo() throws Exception {
        String path = "/internal/actuator/health/foo";
        assertUpStatus(managementClient.get(path).aggregate().join());
        assertThat(armeriaClient.get(path).aggregate().join().status()).isSameAs(HttpStatus.NOT_FOUND);

        path = "/internal/actuator/health/bar";
        assertUpStatus(managementClient.get(path).aggregate().join());
        assertThat(armeriaClient.get(path).aggregate().join().status()).isSameAs(HttpStatus.NOT_FOUND);

        path = "/foohealth";
        assertUpStatus(managementClient.get(path).aggregate().join());
        assertThat(armeriaClient.get(path).aggregate().join().status()).isSameAs(HttpStatus.NOT_FOUND);

        // barhealth is bound to armeria port.
        path = "/barhealth";
        assertThat(managementClient.get(path).aggregate().join().status()).isSameAs(HttpStatus.NOT_FOUND);
        assertUpStatus(armeriaClient.get(path).aggregate().join());
    }
}
