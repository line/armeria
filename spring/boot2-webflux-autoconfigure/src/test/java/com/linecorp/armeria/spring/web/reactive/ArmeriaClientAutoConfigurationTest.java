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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.actuate.metrics.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
public class ArmeriaClientAutoConfigurationTest {

    @SpringBootApplication
    @Configuration
    static class TestConfiguration {

        @Primary
        @Bean
        static MeterRegistry meterRegistry() {
            return PrometheusMeterRegistries.defaultRegistry();
        }

        @RestController
        static class TestController {
            private final org.springframework.web.reactive.function.client.WebClient webClient;

            TestController(org.springframework.web.reactive.function.client.WebClient.Builder builder) {
                webClient = builder.build();
            }

            @GetMapping("/proxy")
            Mono<String> proxy(@RequestParam String port) {
                return webClient.get()
                                .uri("http://127.0.0.1:" + port + "/hello")
                                .retrieve()
                                .bodyToMono(String.class);
            }

            @GetMapping("/hello")
            Mono<String> hello() {
                return Mono.just("hello");
            }
        }
    }

    @LocalServerPort
    int port;

    @Test
    public void shouldGetHelloFromRestController() throws Exception {
        final WebClient client = WebClient.of("http://127.0.0.1:" + port);
        final AggregatedHttpResponse response = client.get("/proxy?port=" + port).aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("hello");
    }

    @Test
    public void testClientMetric() throws Exception {
        final WebClient webClient = WebClient.of("http://127.0.0.1:" + port);
        final AggregatedHttpResponse response = webClient.get("/proxy?port=" + port).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        final String metricReport = webClient.get("/internal/metrics")
                                             .aggregate().join()
                                             .contentUtf8();
        assertThat(metricReport).contains("# TYPE armeria_client_active_requests gauge");
    }
}
