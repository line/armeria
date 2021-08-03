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

package com.linecorp.armeria.spring.tomcat.demo;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import javax.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import com.linecorp.armeria.spring.LocalArmeriaPort;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ErrorHandlingTest {

    @LocalArmeriaPort
    private int port;

    @Inject
    private TestRestTemplate restTemplate;

    private static String baseUrl(int port) {
        return "http://localhost:" + port + "/tomcat/api/rest/v1";
    }

    @Test
    public void runtimeExceptionShouldReturnSpringFormattedMessage() throws Exception {
        final ResponseEntity<String> response =
                restTemplate.getForEntity(baseUrl(port) + "/error-handling/runtime-exception", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThatJson(response.getBody()).node("timestamp").isPresent();
        assertThatJson(response.getBody()).node("status")
                                          .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThatJson(response.getBody()).node("error")
                                          .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
        assertThatJson(response.getBody()).node("message")
                                          .isEqualTo("runtime exception");
        assertThatJson(response.getBody()).node("path")
                                          .isEqualTo("/error-handling/runtime-exception");
    }

    @Test
    public void customExceptionShouldReturnSpringFormattedMessage() throws Exception {
        final ResponseEntity<String> response =
                restTemplate.getForEntity(baseUrl(port) + "/error-handling/custom-exception", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThatJson(response.getBody()).node("timestamp").isPresent();
        assertThatJson(response.getBody()).node("status")
                                          .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThatJson(response.getBody()).node("error")
                                          .isEqualTo(HttpStatus.NOT_FOUND.getReasonPhrase());
        assertThatJson(response.getBody()).node("message")
                                          .isEqualTo("custom not found");
        assertThatJson(response.getBody()).node("path")
                                          .isEqualTo("/error-handling/custom-exception");
    }

    @Test
    public void exceptionHandlerShouldReturnCustomFormattedMessage() throws Exception {
        final ResponseEntity<String> response =
                restTemplate.getForEntity(baseUrl(port) + "/error-handling/exception-handler", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThatJson(response.getBody()).node("timestamp").isAbsent();
        assertThatJson(response.getBody()).node("status")
                                          .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThatJson(response.getBody()).node("error").isAbsent();
        assertThatJson(response.getBody()).node("message")
                                          .isEqualTo("exception handler");
        assertThatJson(response.getBody()).node("path").isAbsent();
    }

    @Test
    public void globalExceptionHandlerShouldReturnCustomFormattedMessage() throws Exception {
        final ResponseEntity<String> response =
                restTemplate.getForEntity(baseUrl(port) + "/error-handling/global-exception-handler",
                                          String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThatJson(response.getBody()).node("timestamp").isAbsent();
        assertThatJson(response.getBody()).node("status")
                                          .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThatJson(response.getBody()).node("error").isAbsent();
        assertThatJson(response.getBody()).node("message")
                                          .isEqualTo("global exception handler");
        assertThatJson(response.getBody()).node("path").isAbsent();
    }
}
