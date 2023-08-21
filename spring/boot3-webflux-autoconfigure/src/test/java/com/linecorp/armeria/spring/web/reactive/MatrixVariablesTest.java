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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.RestController;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import reactor.core.publisher.Flux;

/**
 * Integration test for <a href="https://docs.spring.io/spring-framework/reference/web/webflux/controller/ann-methods/matrix-variables.html">Matrix Variables</a>.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MatrixVariablesTest {

    @SpringBootApplication
    @Configuration
    static class TestConfiguration {
        @RestController
        static class TestController {

            // GET /owners/42;q=11/pets/21;q=22
            // q1 = 11, q2 = 22

            @GetMapping("/owners/{ownerId}/pets/{petId}")
            Flux<Integer> findPet(
                    @MatrixVariable(name = "q", pathVar = "ownerId") int q1,
                    @MatrixVariable(name = "q", pathVar = "petId") int q2) {
                return Flux.just(q1, q2);
            }
        }

        @Bean
        public ArmeriaServerConfigurator serverConfigurator() {
            return sb -> sb.decorator(LoggingService.newDecorator());
        }
    }

    @LocalServerPort
    int port;

    @Test
    void foo() throws Exception {
        final WebClient client = WebClient.of("http://127.0.0.1:" + port);
        final AggregatedHttpResponse response = client.blocking().get("/owners/42;q=11/pets/21;q=22");
        assertThat(response.contentUtf8()).isEqualTo("[11,22]");
    }
}
