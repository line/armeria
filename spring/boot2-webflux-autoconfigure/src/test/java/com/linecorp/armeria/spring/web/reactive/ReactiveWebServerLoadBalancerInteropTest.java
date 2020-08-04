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
import static org.springframework.web.reactive.function.BodyInserters.fromValue;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.HEAD;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

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
            @GetMapping("/controller/api/ping")
            Mono<String> ping() {
                return Mono.just("PONG");
            }
        }

        @Bean
        RouterFunction<ServerResponse> routerFunction() {
            return route(GET("/router/api/poke"), request -> ok().build())
                    .and(route(HEAD("/router/api/poke"), request -> ok().build()))
                    .and(route(HEAD("/router/api/ping"), request -> ok().body(fromValue("PONG"))));
        }
    }

    @LocalServerPort
    int port;

    @Test
    void getToController() throws Exception {
        // TODO: Need to assert that CancelledSubscriptionException is not propagated to HttpWebHandlerAdapter
        final String httpRequest = "GET /controller/api/ping HTTP/1.0\r\n\r\n";
        // Should not be chunked.
        final String expectedHttpResponse =
                "HTTP/1.1 200 OK\r\n" +
                "content-type: text/plain;charset=UTF-8\r\n" +
                "content-length: 4\r\n\r\n" +
                "PONG";
        testHttpResponse(httpRequest, expectedHttpResponse);
    }

    @Test
    void headToController() throws Exception {
        // TODO: Need to assert that CancelledSubscriptionException is not propagated to HttpWebHandlerAdapter
        final String httpRequest = "HEAD /controller/api/ping HTTP/1.0\r\n\r\n";
        // Should not be chunked.
        final String expectedHttpResponse =
                "HTTP/1.1 200 OK\r\n" +
                "content-type: text/plain;charset=UTF-8\r\n" +
                "content-length: 4\r\n\r\n";
        testHttpResponse(httpRequest, expectedHttpResponse);
    }

    @Test
    void getToRouter() throws Exception {
        // TODO: Need to assert that CancelledSubscriptionException is not propagated to HttpWebHandlerAdapter
        final String httpRequest = "GET /router/api/poke HTTP/1.0\r\n\r\n";
        // Should not be chunked.
        final String expectedHttpResponse =
                "HTTP/1.1 200 OK\r\n" +
                "content-length: 0\r\n\r\n";
        testHttpResponse(httpRequest, expectedHttpResponse);
    }

    @Test
    void headToRouterWithoutBody() throws Exception {
        // TODO: Need to assert that CancelledSubscriptionException is not propagated to HttpWebHandlerAdapter
        final String httpRequest = "HEAD /router/api/poke HTTP/1.0\r\n\r\n";
        // Should not be chunked.
        final String expectedHttpResponse =
                "HTTP/1.1 200 OK\r\n" +
                "content-length: 0\r\n\r\n";
        testHttpResponse(httpRequest, expectedHttpResponse);
    }

    @Test
    void headToRouterWithBody() throws Exception {
        // TODO: Need to assert that CancelledSubscriptionException is not propagated to HttpWebHandlerAdapter
        final String httpRequest = "HEAD /router/api/ping HTTP/1.0\r\n\r\n";
        // Should not be chunked.
        final String expectedHttpResponse =
                "HTTP/1.1 200 OK\r\n" +
                "content-type: text/plain;charset=UTF-8\r\n" +
                "content-length: 4\r\n\r\n";
        testHttpResponse(httpRequest, expectedHttpResponse);
    }

    private void testHttpResponse(String httpRequest, String expectedHttpResponse)
            throws Exception {
        try (Socket s = new Socket(NetUtil.LOCALHOST4, port)) {
            s.setSoTimeout(10000);
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();
            out.write(httpRequest.getBytes(StandardCharsets.US_ASCII));

            assertThat(new String(ByteStreams.toByteArray(in))).isEqualTo(expectedHttpResponse);
        }
    }
}
