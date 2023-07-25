/*
 * Copyright 2021 LINE Corporation
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
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.linecorp.armeria.spring.LocalArmeriaPort;

import jakarta.inject.Inject;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ErrorHandlingTest {

    @LocalArmeriaPort
    private int port;

    @Inject
    private TestRestTemplate restTemplate;

    private static String jettyBaseUrlPath(int port) {
        return "http://localhost:" + port + JETTY_BASE_PATH;
    }

    @ParameterizedTest
    @CsvSource({
            "/error-handling/runtime-exception, 500, jakarta.servlet.ServletException: " +
            "Request processing failed: java.lang.RuntimeException: runtime exception",
            "/error-handling/custom-exception, 404, custom not found",
            "/error-handling/exception-handler, 500, exception handler",
            "/error-handling/global-exception-handler, 500, global exception handler"
    })
    void shouldReturnFormattedMessage(String path, int status, String message) throws Exception {
        final ResponseEntity<String> response =
                restTemplate.getForEntity(jettyBaseUrlPath(port) + path, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(status));
        assertThatJson(response.getBody()).node("status").isEqualTo(status);
        assertThatJson(response.getBody()).node("message").isEqualTo(message);
    }
}
