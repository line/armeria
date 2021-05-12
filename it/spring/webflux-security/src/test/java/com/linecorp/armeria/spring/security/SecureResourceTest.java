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
 * under the License
 */

package com.linecorp.armeria.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.cookie.CookieClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.MediaType;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SecureResourceTest {
    @LocalServerPort
    int port;

    WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.builder("http://127.0.0.1:" + port)
                          .decorator(LoggingClient.newDecorator())
                          .decorator(CookieClient.newDecorator())
                          .build();

        final AggregatedHttpResponse response =
                client.prepare()
                      .post("/login")
                      .content(MediaType.FORM_DATA, "username=user&password=password")
                      .execute()
                      .aggregate().join();
        assertThat(response.headers().cookies().stream().anyMatch(cookie -> "SESSION".equals(cookie.name())))
                .isTrue();
    }

    @Test
    void testMono() {
        final AggregatedHttpResponse response = client.get("/api/mono").aggregate().join();
        assertThat(response.contentUtf8()).contains("Username=user");
    }

    @Test
    void testFlux() throws InterruptedException {
        final AggregatedHttpResponse response = client.get("/api/flux").aggregate().join();
        assertThat(response.contentUtf8()).contains("Username=user");
    }
}
