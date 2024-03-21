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

package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.health.AbstractReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.spring.web.reactive.ArmeriaSpringActuatorReactiveIndicatorTest.TestConfiguration;

import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = TestConfiguration.class)
@ActiveProfiles("test_actuator")
class ArmeriaSpringActuatorReactiveIndicatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private static final AtomicBoolean healthy = new AtomicBoolean(true);

    @SpringBootApplication
    static class TestConfiguration {

        @Component
        static class TestReactiveHealthIndicator extends AbstractReactiveHealthIndicator {

            @Override
            protected Mono<Health> doHealthCheck(Health.Builder builder) {
                return Mono.fromSupplier(() -> {
                    if (healthy.get()) {
                        return builder.up().build();
                    }
                    return builder.down().build();
                });
            }
        }
    }

    @LocalServerPort
    int port;

    @Test
    void testHealth() throws Exception {
        final BlockingWebClient client = WebClient.of("http://127.0.0.1:" + port).blocking();
        AggregatedHttpResponse res = client.get("/internal/actuator/health");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType().toString()).isEqualTo(ApiVersion.V3.getProducedMimeType().toString());
        Map<String, Object> values = OBJECT_MAPPER.readValue(res.content().array(), JSON_MAP);
        assertThat(values).containsEntry("status", "UP");

        healthy.set(false);

        res = client.get("/internal/actuator/health");
        assertThat(res.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.contentType().toString()).isEqualTo(ApiVersion.V3.getProducedMimeType().toString());
        values = OBJECT_MAPPER.readValue(res.content().array(), JSON_MAP);
        assertThat(values).containsEntry("status", "DOWN");
    }
}
