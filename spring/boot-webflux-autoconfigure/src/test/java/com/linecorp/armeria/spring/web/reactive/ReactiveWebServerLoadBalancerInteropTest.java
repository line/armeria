/*
 * Copyright 2019 LINE Corporation
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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.io.ByteStreams;

import io.netty.util.NetUtil;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test_loadbalancer")
class ReactiveWebServerLoadBalancerInteropTest {

    @SpringBootApplication
    @Configuration
    static class TestConfiguration {
        @RestController
        static class TestController {
            @GetMapping("/api/ping")
            Mono<String> ping() {
                return Mono.just("PONG");
            }
        }
    }

    @LocalServerPort
    int port;

    @Test
    void get() throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST4, port)) {
            s.setSoTimeout(10000);
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();
            out.write("GET /api/ping HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

            // Should not be chunked.
            assertThat(new String(ByteStreams.toByteArray(in))).isEqualTo(
                    "HTTP/1.1 200 OK\r\n" +
                    "content-type: text/plain;charset=UTF-8\r\n" +
                    "content-length: 4\r\n\r\n" +
                    "PONG");
        }
    }

    @Test
    void head() throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST4, port)) {
            s.setSoTimeout(10000);
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();
            out.write("HEAD /api/ping HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

            // Should neither be chunked nor have content.
            assertThat(new String(ByteStreams.toByteArray(in))).isEqualTo(
                    "HTTP/1.1 200 OK\r\n" +
                    "content-type: text/plain;charset=UTF-8\r\n" +
                    "content-length: 4\r\n\r\n");
        }
    }
}
