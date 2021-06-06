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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ReactiveWebServerCompressionLeakTest {

    private static final List<NettyDataBuffer> nettyData = new ArrayList<>();

    @SpringBootApplication
    @Configuration
    static class TestConfiguration {

        @Bean
        public RouterFunction<ServerResponse> route(TestHandler testHandler) {
            return RouterFunctions.route(RequestPredicates.GET("/hello"), testHandler::hello);
        }

        @Component
        static class TestHandler {
            Mono<ServerResponse> hello(ServerRequest request) {
                return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
                                     .body(BodyInserters.fromValue("Hello Armeria"));
            }
        }

        @Bean
        public ArmeriaServerConfigurator configurator() {
            return builder -> builder.decorator(EncodingService.builder()
                                                               .minBytesToForceChunkedEncoding(5)
                                                               .newDecorator());
        }

        @Component
        private static final class HttpDataCaptor implements WebFilter {

            @Override
            public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
                final ServerHttpResponseDecorator httpResponseDecorator =
                        new ServerHttpResponseDecorator(exchange.getResponse()) {
                            @Override
                            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                                final Mono<? extends DataBuffer> buffer = Mono.from(body);
                                return super.writeWith(buffer.doOnNext(b -> {
                                    assert b instanceof NettyDataBuffer;
                                    nettyData.add((NettyDataBuffer) b);
                                }));
                            }
                        };
                return chain.filter(exchange.mutate().response(httpResponseDecorator).build());
            }
        }
    }

    @LocalServerPort
    int port;

    @Test
    void nettyDataBufferShouldBeReleaseWhenCompressionEnabled() throws Exception {
        final WebClient client = webClient();
        final AggregatedHttpResponse response = client.get("/hello").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("Hello Armeria");
        assertThat(nettyData.size()).isOne();
        assertThat(nettyData.get(0).getNativeBuffer().refCnt()).isZero();
    }

    private WebClient webClient() {
        return WebClient.builder("http://127.0.0.1:" + port)
                        .decorator(DecodingClient.newDecorator())
                        .build();
    }
}
