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

import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test_loadbalancer")
class ReactiveWebServerLoadBalancerInteropTest {

    @SpringBootApplication
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
            return route(GET("/router/api/ping"), request -> ok().body(fromValue("PONG")))
                    .and(route(HEAD("/router/api/ping"), request -> ok().body(fromValue("PONG")))
                    .and(route(HEAD("/router/api/poke"), request -> ok().build())));
        }
    }

    @LocalServerPort
    int port;

    final Logger httpWebHandlerAdapterLogger = (Logger) LoggerFactory.getLogger(HttpWebHandlerAdapter.class);
    final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    public void attachAppender() {
        logAppender.start();
        httpWebHandlerAdapterLogger.addAppender(logAppender);
    }

    @AfterEach
    public void detachAppender() {
        httpWebHandlerAdapterLogger.detachAppender(logAppender);
        logAppender.list.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "H1C, /controller/api/ping",
            "H2C, /router/api/ping"
    })
    void testGet(SessionProtocol sessionProtocol, String path) {
        final String uri = sessionProtocol.uriText() + "://127.0.0.1:" + port;
        final WebClient webClient = WebClient.of(uri);
        final AggregatedHttpResponse res = webClient.get(path).aggregate().join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentType()).isSameAs(MediaType.PLAIN_TEXT_UTF_8);
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("4");
        assertThat(res.content().toStringUtf8()).isEqualTo("PONG");
        assertNoErrorLogByHttpWebHandlerAdapter();
    }

    @ParameterizedTest
    @CsvSource({
            "H1C, /controller/api/ping, 4",
            "H2C, /controller/api/ping, 4",
            "H1C, /router/api/ping, 4",
            "H2C, /router/api/ping, 4",
            "H2C, /router/api/poke, 0",
            "H1C, /router/api/poke, 0"
    })
    void testHead(SessionProtocol sessionProtocol, String path, int contentLength) {
        final String uri = sessionProtocol.uriText() + "://127.0.0.1:" + port;
        final WebClient webClient = WebClient.of(uri);
        final AggregatedHttpResponse res = webClient.head(path).aggregate().join();

        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.content().isEmpty()).isTrue();

        if (contentLength > 0) {
            assertThat(res.contentType()).isSameAs(MediaType.PLAIN_TEXT_UTF_8);
            assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH))
                    .isEqualTo(String.valueOf(contentLength));
        }
        assertNoErrorLogByHttpWebHandlerAdapter();
    }

    private void assertNoErrorLogByHttpWebHandlerAdapter() {
        // Example error log for CancelledSubscriptionException by HttpWebHandlerAdapter:
        //
        // Error [com.linecorp.armeria.common.stream.CancelledSubscriptionException] for
        // HTTP HEAD "/router/api/poke", but ServerHttpResponse already committed (200 OK)
        final String errorLogSubString =
                "Error [com.linecorp.armeria.common.stream.CancelledSubscriptionException] for HTTP HEAD";
        assertThat(logAppender.list
                           .stream()
                           .filter(event -> event.getFormattedMessage().contains(errorLogSubString))
                           .collect(Collectors.toList()))
                .isEmpty();
    }
}
