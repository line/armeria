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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ArmeriaAutoConfigurationWithConsumerTest {

    @SpringBootApplication
    @Configuration
    public static class TestConfiguration {
        @Bean
        public Consumer<ServerBuilder> customizer() {
            return sb -> sb.service("/customizer", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    }

    @LocalServerPort
    int port;

    @Test
    void normal() throws Exception {
        final HttpResponse response = WebClient.of("h2c://127.0.0.1:" + port).get("/customizer");
        final AggregatedHttpResponse msg = response.aggregate().get();
        assertThat(msg.status()).isEqualTo(HttpStatus.OK);
    }
}
