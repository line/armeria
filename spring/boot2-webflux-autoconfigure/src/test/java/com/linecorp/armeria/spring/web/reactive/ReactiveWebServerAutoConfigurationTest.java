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
package com.linecorp.armeria.spring.web.reactive;

import static com.linecorp.armeria.common.MediaType.JSON_UTF_8;
import static com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServiceRequestContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test_reactive")
class ReactiveWebServerAutoConfigurationTest {

    @SpringBootApplication
    @Configuration
    static class TestConfiguration {
        @RestController
        static class TestController {
            @GetMapping("/hello")
            Flux<String> hello() {
                // This method would be called in one of Armeria worker threads.
                assertThat(ServiceRequestContext.current()).isNotNull();
                return Flux.just("h", "e", "l", "l", "o");
            }
        }

        @Bean
        public RouterFunction<ServerResponse> route(TestHandler testHandler) {
            return RouterFunctions
                    .route(RequestPredicates.GET("/route")
                                            .and(RequestPredicates.accept(MediaType.TEXT_PLAIN)),
                           testHandler::route)
                    .andRoute(RequestPredicates.POST("/route2")
                                               .and(RequestPredicates.contentType(MediaType.APPLICATION_JSON)),
                              testHandler::route2)
                    .andRoute(RequestPredicates.HEAD("/route3"), request -> ServerResponse.ok().build());
        }

        @Component
        static class TestHandler {
            Mono<ServerResponse> route(ServerRequest request) {
                assertThat(ServiceRequestContext.current()).isNotNull();
                assertThat(request.remoteAddress()).isNotEmpty();
                return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
                                     .body(BodyInserters.fromValue("route"));
            }

            Mono<ServerResponse> route2(ServerRequest request) {
                assertThat(ServiceRequestContext.current()).isNotNull();
                assertThat(request.remoteAddress()).isNotEmpty();
                return Mono.from(request.bodyToMono(Map.class))
                           .map(map -> assertThat(map.get("a")).isEqualTo(1))
                           .then(ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                                               .body(BodyInserters.fromValue("[\"route\"]")));
            }
        }
    }

    private static final ClientFactory clientFactory =
            ClientFactory.builder()
                         .tlsNoVerify()
                         .addressResolverGroupFactory(eventLoopGroup -> MockAddressResolverGroup.localhost())
                         .build();

    @LocalServerPort
    int port;

    @ParameterizedTest
    @ArgumentsSource(SchemesProvider.class)
    void shouldGetHelloFromRestController(String scheme) throws Exception {
        final WebClient client = WebClient.builder(scheme + "://example.com:" + port)
                                          .factory(clientFactory)
                                          .build();
        final AggregatedHttpResponse response = client.get("/hello").aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo("hello");
    }

    @ParameterizedTest
    @ArgumentsSource(SchemesProvider.class)
    void shouldGetHelloFromRouter(String scheme) throws Exception {
        final WebClient client = WebClient.builder(scheme + "://example.com:" + port)
                                          .factory(clientFactory)
                                          .build();

        final AggregatedHttpResponse res = client.get("/route").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("route");

        final AggregatedHttpResponse res2 =
                client.execute(RequestHeaders.of(HttpMethod.POST, "/route2",
                                                 HttpHeaderNames.CONTENT_TYPE, JSON_UTF_8),
                               HttpData.wrap("{\"a\":1}".getBytes())).aggregate().join();
        assertThatJson(res2.contentUtf8()).isArray()
                                          .ofLength(1)
                                          .thatContains("route");
    }

    @ParameterizedTest
    @ArgumentsSource(SchemesProvider.class)
    void shouldGetNotFound(String scheme) {
        final WebClient client = WebClient.builder(scheme + "://example.com:" + port)
                                          .factory(clientFactory)
                                          .build();
        assertThat(client.get("/route2").aggregate().join().status()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(client.execute(
                RequestHeaders.of(HttpMethod.POST, "/route2",
                                  HttpHeaderNames.CONTENT_TYPE, PLAIN_TEXT_UTF_8),
                HttpData.wrap("text".getBytes())).aggregate().join().status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static class SchemesProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(SessionProtocol.H1, SessionProtocol.H2)
                         .map(SessionProtocol::uriText)
                         .map(Arguments::of);
        }
    }
}
