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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.internal.MockAddressResolverGroup;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test_reactive")
public class ReactiveWebServerAutoConfigurationTest {

    @SpringBootApplication
    @Configuration
    static class TestConfiguration {
        @RestController
        static class TestController {
            @GetMapping("/hello")
            Flux<String> hello() {
                // This method would be called in one of Armeria worker threads.
                assertThat((ServiceRequestContext) RequestContext.current()).isNotNull();
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
            public Mono<ServerResponse> route(ServerRequest request) {
                assertThat((ServiceRequestContext) RequestContext.current()).isNotNull();
                return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
                                     .body(BodyInserters.fromObject("route"));
            }

            public Mono<ServerResponse> route2(ServerRequest request) {
                assertThat((ServiceRequestContext) RequestContext.current()).isNotNull();
                return Mono.from(request.bodyToMono(Map.class))
                           .map(map -> assertThat(map.get("a")).isEqualTo(1))
                           .then(ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                                               .body(BodyInserters.fromObject("[\"route\"]")));
            }
        }
    }

    private static final ClientFactory clientFactory =
            new ClientFactoryBuilder()
                    .sslContextCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE))
                    .addressResolverGroupFactory(eventLoopGroup -> MockAddressResolverGroup.localhost())
                    .build();

    // "@RunWith(SpringJUnit4ClassRunner.class)" is specified, so use the following stream instead of
    // specifying "@RunWith(Parameterized.class)".
    private static final List<String> protocols = Stream.of(SessionProtocol.H1, SessionProtocol.H2)
                                                        .map(SessionProtocol::uriText)
                                                        .collect(ImmutableList.toImmutableList());

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @LocalServerPort
    int port;

    @Test
    public void shouldGetHelloFromRestController() throws Exception {
        protocols.forEach(scheme -> {
            final HttpClient client = HttpClient.of(clientFactory, scheme + "://example.com:" + port);
            final AggregatedHttpMessage response = client.get("/hello").aggregate().join();
            assertThat(response.contentUtf8()).isEqualTo("hello");
        });
    }

    @Test
    public void shouldGetHelloFromRouter() throws Exception {
        protocols.forEach(scheme -> {
            final HttpClient client = HttpClient.of(clientFactory, scheme + "://example.com:" + port);

            final AggregatedHttpMessage res = client.get("/route").aggregate().join();
            assertThat(res.contentUtf8()).isEqualTo("route");

            final AggregatedHttpMessage res2 =
                    client.execute(HttpHeaders.of(HttpMethod.POST, "/route2")
                                              .contentType(com.linecorp.armeria.common.MediaType.JSON_UTF_8),
                                   HttpData.of("{\"a\":1}".getBytes())).aggregate().join();
            assertThatJson(res2.contentUtf8()).isArray()
                                              .ofLength(1)
                                              .thatContains("route");
        });
    }

    @Test
    public void shouldGetNotFound() {
        protocols.forEach(scheme -> {
            final HttpClient client = HttpClient.of(clientFactory, scheme + "://example.com:" + port);
            assertThat(client.get("/route2").aggregate().join().status()).isEqualTo(HttpStatus.NOT_FOUND);

            assertThat(client.execute(
                    HttpHeaders.of(HttpMethod.POST, "/route2")
                               .contentType(com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8),
                    HttpData.of("text".getBytes())).aggregate().join().status())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        });
    }
}
