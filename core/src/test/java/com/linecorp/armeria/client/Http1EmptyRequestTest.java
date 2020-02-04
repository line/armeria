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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

/**
 * Makes sure an empty HTTP/1 request is sent with or without the {@code content-length} header
 * for all HTTP methods.
 */
class Http1EmptyRequestTest {

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, mode = Mode.EXCLUDE, names = "UNKNOWN")
    void emptyRequest(HttpMethod method) throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            final int port = ss.getLocalPort();

            final WebClient client = WebClient.of("h1c://127.0.0.1:" + port);
            client.execute(HttpRequest.of(method, "/")).aggregate();

            try (Socket s = ss.accept()) {
                final BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                assertThat(in.readLine()).isEqualTo(method.name() + " / HTTP/1.1");
                assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                assertThat(in.readLine()).startsWith("user-agent: armeria/");
                if (hasContent(method)) {
                    assertThat(in.readLine()).isEqualTo("content-length: 0");
                }
                assertThat(in.readLine()).isEmpty();
            }
        }
    }

    private static boolean hasContent(HttpMethod method) {
        switch (method) {
            case OPTIONS:
            case GET:
            case HEAD:
            case DELETE:
            case TRACE:
            case CONNECT:
                return false;
            case POST:
            case PUT:
            case PATCH:
                return true;
            default:
                return false;
        }
    }
}
