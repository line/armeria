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
package com.linecorp.armeria.spring.tomcat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.spring.LocalArmeriaPort;

/**
 * Integration test for <a href="https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/matrix-variables.html">Matrix Variables</a>.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("testbed")
class MatrixVariablesTest {

    static final String TOMCAT_BASE_PATH = "/tomcat/api/rest/v1";

    @LocalArmeriaPort
    int port;

    @Test
    void matrixVariablesPreserved() throws Exception {
        final WebClient client = WebClient.of("http://127.0.0.1:" + port);
        final AggregatedHttpResponse response = client.blocking().get(
                TOMCAT_BASE_PATH + "/owners/42;q=11/pets/21;q=22");
        assertThat(response.contentUtf8()).isEqualTo("[11,22]");
    }

    @Test
    void wrongMatrixVariables() throws Exception {
        final WebClient client = WebClient.of("http://127.0.0.1:" + port);
        AggregatedHttpResponse response = client.blocking().get(
                TOMCAT_BASE_PATH + ";/owners/42;q=11/pets/21;q=22");
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);

        response = client.blocking().get("/tomcat;wrong=place/api/rest/v1/owners/42;q=11/pets/21;q=22");
        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);
    }
}
