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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.linecorp.armeria.spring.LocalArmeriaPort;

/**
 * Tests for {@link LocalArmeriaPort}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class LocalArmeriaPortTest {

    @SpringBootApplication
    static class TestConfiguration {
    }

    @LocalServerPort
    int port;

    @LocalArmeriaPort
    int armeriaPort;

    @Test
    void testSamePort() {
        assertThat(port).isEqualTo(armeriaPort);
    }
}
