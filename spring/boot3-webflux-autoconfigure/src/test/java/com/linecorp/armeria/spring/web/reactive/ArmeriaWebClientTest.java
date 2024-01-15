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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test_reactive")
class ArmeriaWebClientTest {

    @SpringBootApplication
    @ActiveProfiles("test_reactive")
    static class TestConfiguration {
        @RestController
        static class TestController {

            @GetMapping("/hello")
            Flux<String> hello() {
                ensureInContextAwareEventLoop();
                return Flux.just("hello", "\n", "armeria", "\n", "world");
            }

            @GetMapping("/conflict")
            @ResponseStatus(HttpStatus.CONFLICT)
            Mono<Void> conflict() {
                ensureInContextAwareEventLoop();
                // In Spring 6.1, the result type should be wrapped with Mono, Flux, Publisher or
                // CompletableFuture so that the request is executed in the context-aware event loop.
                // Otherwise, the request is executed in the blocking task executor of Spring WebFlux.
                // https://github.com/spring-projects/spring-framework/blob/0c42965fc36f19868fbba382b2e03ed172087438/spring-webflux/src/main/java/org/springframework/web/reactive/result/method/annotation/RequestMappingHandlerAdapter.java#L264-L266
                return Mono.empty();
            }

            @GetMapping("/resource")
            Mono<ClassPathResource> resource() {
                ensureInContextAwareEventLoop();
                return Mono.just(new ClassPathResource("/testing/webflux/largeTextFile.txt", getClass()));
            }

            @PostMapping("/birthday")
            Mono<Person> birthday(@RequestBody Person person) {
                ensureInContextAwareEventLoop();
                return Mono.just(new Person(person.name(), person.age() + 1));
            }

            private static void ensureInContextAwareEventLoop() {
                assertThat(ServiceRequestContext.current()).isNotNull();
            }
        }

        @Bean
        static ArmeriaClientConfigurator configurator1() {
            return client -> client.responseTimeoutMillis(0);
        }

        @Bean
        static ArmeriaServerConfigurator configurator2() {
            return server -> server.requestTimeoutMillis(0);
        }
    }

    @LocalServerPort
    int port;

    static ClientFactory clientFactory;
    static WebClient webClient;

    @BeforeAll
    public static void beforeAll() {
        clientFactory =
                ClientFactory.builder()
                             .tlsNoVerify()
                             .addressResolverGroupFactory(unused -> MockAddressResolverGroup.localhost())
                             .build();
        webClient = WebClient.builder().clientConnector(
                new ArmeriaClientHttpConnector(builder -> builder.factory(clientFactory))).build();
    }

    @AfterAll
    public static void afterAll() {
        clientFactory.closeAsync();
    }

    private String uri(String path) {
        return "https://example.com:" + port + path;
    }

    @Test
    void getHello() {
        final Flux<String> body =
                webClient.get()
                         .uri(uri("/hello"))
                         .retrieve()
                         .bodyToFlux(String.class);
        StepVerifier.create(body)
                    .expectNext("hello")
                    .expectNext("armeria")
                    .expectNext("world")
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
    }

    @Test
    void getConflict() {
        final Mono<ClientResponse> response =
                webClient.get()
                         .uri(uri("/conflict"))
                         .exchangeToMono(Mono::just);
        StepVerifier.create(response)
                    .assertNext(r -> assertThat(r.statusCode()).isEqualTo(HttpStatus.CONFLICT))
                    .expectComplete()
                    .verify(Duration.ofSeconds(1000000));
    }

    @Test
    void getConflictUsingBodyToMono() {
        @SuppressWarnings("Convert2MethodRef")
        final Mono<String> response =
                webClient.get()
                         .uri(uri("/conflict"))
                         .retrieve()
                         .onStatus(status -> status.isError(),
                                   resp -> resp.bodyToMono(String.class).map(Exception::new))
                         .bodyToMono(String.class);
        StepVerifier.create(response)
                    .expectError()
                    .verify(Duration.ofSeconds(10));
    }

    @Test
    void getResource() {
        final Flux<DataBuffer> body =
                webClient.get()
                         .uri(uri("/resource"))
                         .retrieve()
                         .bodyToFlux(DataBuffer.class);
        StepVerifier.create(body)
                    .thenConsumeWhile(data -> data.readableByteCount() > 0)
                    // An empty buffer comes last.
                    .assertNext(data -> assertThat(data.readableByteCount()).isZero())
                    .expectComplete()
                    .verify(Duration.ofSeconds(30));
    }

    @Test
    void postPerson() {
        final Mono<Person> body =
                webClient.post()
                         .uri(uri("/birthday"))
                         .contentType(MediaType.APPLICATION_JSON)
                         .body(Mono.just(new Person("armeria", 4)), Person.class)
                         .retrieve()
                         .bodyToMono(Person.class);
        StepVerifier.create(body)
                    .expectNext(new Person("armeria", 5))
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
    }

    private static class Person {
        private final String name;
        private final int age;

        @JsonCreator
        Person(@JsonProperty("name") String name,
               @JsonProperty("age") int age) {
            this.name = name;
            this.age = age;
        }

        @JsonProperty
        public String name() {
            return name;
        }

        @JsonProperty
        public int age() {
            return age;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Person)) {
                return false;
            }
            final Person that = (Person) o;
            return age == that.age && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + age;
        }
    }
}
