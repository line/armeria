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

package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.spring.ArmeriaSettingsBaseContextPathTest.TestConfiguration;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "baseContextPathTest" })
@DirtiesContext
class ArmeriaSettingsBaseContextPathTest {

    @SpringBootApplication
    static class TestConfiguration {
        @Bean
        ArmeriaServerConfigurator armeriaServerConfigurator() {
            return server -> {
                server.service("/hello", (ctx, req) -> {
                    return HttpResponse.of("Hello, world!");
                });
            };
        }
    }

    @LocalArmeriaPort
    int port;

    @Test
    void shouldServiceRequestsOnBaseContextPath() {
        final BlockingWebClient client = BlockingWebClient.of("http://127.0.0.1:" + port);
        assertThat(client.get("/hello").status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.get("/foo/hello").status()).isEqualTo(HttpStatus.OK);
    }
}
