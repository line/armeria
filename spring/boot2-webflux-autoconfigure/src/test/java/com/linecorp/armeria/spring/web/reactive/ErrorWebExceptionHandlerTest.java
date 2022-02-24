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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient.Builder;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@EnableConfigurationProperties({
        ServerProperties.class,
        WebProperties.class
})
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ErrorWebExceptionHandlerTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hello", (ctx, req) -> HttpResponse.of(
                    com.linecorp.armeria.common.HttpStatus.SERVICE_UNAVAILABLE));
        }
    };

    @SpringBootApplication
    @Configuration
    static class TestConfiguration {

        @RestController
        static class TestController {
            private final org.springframework.web.reactive.function.client.WebClient webClient;

            TestController(Builder builder) {
                webClient = builder.build();
            }

            @GetMapping("/proxy")
            Flux<byte[]> proxy(@RequestParam String port) {
                return webClient.get()
                                .uri("http://127.0.0.1:" + port + "/hello")
                                .retrieve()
                                .onStatus(HttpStatus::isError, res -> {
                                    return Mono.error(new AnticipatedException());
                                })
                                .bodyToFlux(byte[].class);
            }
        }

        @Component
        static class CustomExceptionHandler extends AbstractErrorWebExceptionHandler {

            CustomExceptionHandler(ErrorAttributes errorAttributes,
                                   WebProperties webProperties,
                                   ApplicationContext applicationContext,
                                   ServerCodecConfigurer serverCodecConfigurer) {
                super(errorAttributes, webProperties.getResources(), applicationContext);
                setMessageWriters(serverCodecConfigurer.getWriters());
                setMessageReaders(serverCodecConfigurer.getReaders());
            }

            @Override
            protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
                return RouterFunctions.route(
                        RequestPredicates.all(), req -> {
                            final Throwable error = errorAttributes.getError(req);
                            if (error instanceof AnticipatedException) {
                                return ServerResponse.status(HttpStatus.BAD_REQUEST)
                                                     .bodyValue("CustomExceptionHandler");
                            }
                            return ServerResponse.ok().build();
                        }
                );
            }
        }
    }

    @LocalServerPort
    int proxyPort;

    @Test
    void customExceptionHandlerResponse() throws Exception {
        final WebClient client = WebClient.of("http://127.0.0.1:" + proxyPort);
        final AggregatedHttpResponse response = client.get("/proxy?port=" + server.httpPort())
                                                      .aggregate()
                                                      .join();
        assertThat(response.status()).isSameAs(com.linecorp.armeria.common.HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).isEqualTo("CustomExceptionHandler");
    }
}
