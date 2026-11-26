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

package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.spring.web.reactive.ArmeriaSpringActuatorControllerEndpointTest.TestConfiguration;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = TestConfiguration.class,
        properties = "management.endpoints.web.exposure.include=health,custom")
@ActiveProfiles("test_actuator")
class ArmeriaSpringActuatorControllerEndpointTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    @SpringBootApplication
    static class TestConfiguration {
        @Bean
        CustomControllerEndpoint customControllerEndpoint() {
            return new CustomControllerEndpoint();
        }
    }

    @SuppressWarnings({ "deprecation", "removal" })
    @RestControllerEndpoint(id = "custom")
    static class CustomControllerEndpoint {
        @GetMapping
        String get() {
            return "custom";
        }
    }

    @LocalServerPort
    int port;

    @Test
    void linksIncludeControllerEndpoints() throws Exception {
        final BlockingWebClient client = WebClient.of("http://127.0.0.1:" + port).blocking();
        final AggregatedHttpResponse res = client.get("/internal/actuator");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final Map<String, Object> values = OBJECT_MAPPER.readValue(res.content().array(), JSON_MAP);
        assertThat(values).extractingByKey("_links", as(MAP)).containsKeys("health", "custom");

        final AggregatedHttpResponse custom = client.get("/internal/actuator/custom");
        assertThat(custom.status()).isEqualTo(HttpStatus.OK);
        assertThat(custom.contentUtf8()).isEqualTo("custom");
    }
}
