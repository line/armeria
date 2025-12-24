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

package com.linecorp.armeria.spring.tomcat;

import static com.linecorp.armeria.spring.tomcat.MatrixVariablesTest.TOMCAT_BASE_PATH;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import com.linecorp.armeria.spring.LocalArmeriaPort;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ErrorHandlingTest {

    @LocalArmeriaPort
    private int port;

    private static String tomcatBaseUrlPath(int port) {
        return "http://localhost:" + port + TOMCAT_BASE_PATH;
    }

    @ParameterizedTest
    @CsvSource({
            "/error-handling/runtime-exception, 500, runtime exception",
            "/error-handling/custom-exception, 404, custom not found",
            "/error-handling/exception-handler, 500, exception handler",
            "/error-handling/global-exception-handler, 500, global exception handler"
    })
    void shouldReturnFormattedMessage(String path, int status, String message) throws Exception {
        final RestTestClient restTestClient =
                RestTestClient.bindToServer().baseUrl(tomcatBaseUrlPath(port) + path).build();
        final ResponseSpec responseSpec = restTestClient.get().exchange();
        final ExchangeResult exchangeResult = responseSpec.returnResult();
        assertThat(exchangeResult.getStatus()).isEqualTo(HttpStatus.valueOf(status));
        assertThatJson(new String(exchangeResult.getResponseBodyContent())).node("status").isEqualTo(status);
        assertThatJson(new String(exchangeResult.getResponseBodyContent())).node("message").isEqualTo(message);
    }
}
