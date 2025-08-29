/*
 * Copyright 2024 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.ObservationHandler;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "armeria.graceful-shutdown-quiet-period-millis=0")
class ObservationTest {

    private static final AtomicReference<String> ctxStatusRef = new AtomicReference<>();

    @SpringBootApplication
    @Configuration
    static class TestConfiguration {
        @RestController
        static class TestController {
            @GetMapping("/hello")
            Mono<String> hello(@RequestParam String mode) {
                return switch (mode) {
                    case "throw" -> throw new RuntimeException("exception thrown");
                    case "success" -> Mono.just("world");
                    case "timeout" -> Mono.never();
                    default -> throw new RuntimeException("Unexpected mode: " + mode);
                };
            }
        }

        @Bean
        public ArmeriaServerConfigurator serverConfigurator() {
            return sb -> sb.decorator(LoggingService.newDecorator())
                           .requestTimeout(Duration.ofSeconds(1));
        }

        @Bean
        public ObservationHandler<Context> observationHandler() {
            return new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Context context) {
                    return true;
                }

                @Override
                public void onStop(Context context) {
                    final KeyValue keyValue = context.getLowCardinalityKeyValue("status");
                    if (keyValue != null) {
                        ctxStatusRef.set(keyValue.getValue());
                    }
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void beforeEach() {
        ctxStatusRef.set(null);
    }

    @Test
    void ok() throws Exception {
        final WebClient client = WebClient.of("http://127.0.0.1:" + port);
        final AggregatedHttpResponse response = client.blocking().get("/hello?mode=success");
        assertThat(response.status().code()).isEqualTo(200);
        assertThat(response.contentUtf8()).isEqualTo("world");

        await().untilAsserted(() -> assertThat(ctxStatusRef.get()).isNotNull());
        final String ctxStatus = ctxStatusRef.get();
        assertThat(ctxStatus).isNotNull();
        assertThat(ctxStatus).isEqualTo("200");
    }

    @Test
    void throwsException() throws Exception {
        final WebClient client = WebClient.of("http://127.0.0.1:" + port);
        final AggregatedHttpResponse response = client.blocking().get("/hello?mode=throw");
        assertThat(response.status().code()).isEqualTo(500);

        await().untilAsserted(() -> assertThat(ctxStatusRef.get()).isNotNull());
        final String ctxStatus = ctxStatusRef.get();
        assertThat(ctxStatus).isNotNull();
        assertThat(ctxStatus).isEqualTo("500");
    }

    @Test
    void timesOut() throws Exception {
        final WebClient client = WebClient.of("http://127.0.0.1:" + port);
        final AggregatedHttpResponse response = client.blocking().get("/hello?mode=timeout");
        assertThat(response.status().code()).isEqualTo(503);

        await().untilAsserted(() -> assertThat(ctxStatusRef.get()).isNotNull());
        final String ctxStatus = ctxStatusRef.get();
        assertThat(ctxStatus).isNotNull();
        assertThat(ctxStatus).isEqualTo("503");
    }
}
