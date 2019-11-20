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
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class ArmeriaClientHttpRequestTest {

    private static final String TEST_PATH_AND_QUERY = "/index.html?q=1";

    static WebClient webClient;

    @BeforeClass
    public static void beforeClass() {
        webClient = mock(WebClient.class);
        when(webClient.execute((HttpRequest) any())).thenReturn(HttpResponse.of(HttpStatus.OK));
    }

    private static ArmeriaClientHttpRequest request() {
        return new ArmeriaClientHttpRequest(webClient, HttpMethod.GET, TEST_PATH_AND_QUERY,
                                            URI.create("http://localhost"), DataBufferFactoryWrapper.DEFAULT);
    }

    @Test
    public void completeWithoutBody() {
        final ArmeriaClientHttpRequest request = request();
        request.setComplete().subscribe();

        // Wait until calling HttpClient#execute.
        await().until(() -> request.future().isDone());

        // Consume from Armeria HttpRequest.
        final HttpRequest httpRequest = request.request();
        assertThat(httpRequest).isNotNull();
        assertThat(httpRequest.completionFuture().isDone()).isFalse();

        // Completed when a subscriber subscribed.
        StepVerifier.create(httpRequest).expectComplete().verify();

        await().until(() -> httpRequest.completionFuture().isDone());
    }

    @Test
    public void writeWithPublisher() {
        final ArmeriaClientHttpRequest request = request();
        final Flux<DataBuffer> body = Flux.just("a", "b", "c", "d", "e")
                                          .map(String::getBytes)
                                          .map(DataBufferFactoryWrapper.DEFAULT.delegate()::wrap);

        assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
        request.getHeaders().add(HttpHeaderNames.USER_AGENT.toString(), "spring/armeria");
        request.getCookies().add("a", new HttpCookie("a", "1"));

        request.writeWith(body)
               .then(Mono.defer(request::setComplete))
               .subscribe();

        // Wait until calling HttpClient#execute.
        await().until(() -> request.future().isDone());

        // Consume from Armeria HttpRequest.
        final HttpRequest httpRequest = request.request();
        assertThat(httpRequest).isNotNull();

        // Check the headers.
        final RequestHeaders headers = httpRequest.headers();
        assertThat(headers.method()).isEqualTo(com.linecorp.armeria.common.HttpMethod.GET);
        assertThat(headers.path()).isEqualTo(TEST_PATH_AND_QUERY);
        assertThat(headers.get(HttpHeaderNames.ACCEPT)).isEqualTo("*/*");
        assertThat(headers.get(HttpHeaderNames.USER_AGENT)).isEqualTo("spring/armeria");
        assertThat(headers.get(HttpHeaderNames.COOKIE)).isEqualTo("a=1");

        assertThat(httpRequest.completionFuture().isDone()).isFalse();

        // Armeria HttpRequest produces http body only.
        final Flux<String> requestBody =
                Flux.from(httpRequest).cast(HttpData.class).map(HttpData::toStringUtf8);
        StepVerifier.create(requestBody, 1)
                    .expectNext("a").thenRequest(1)
                    .expectNext("b").thenRequest(1)
                    .expectNext("c").thenRequest(1)
                    .expectNext("d").thenRequest(1)
                    .expectNext("e").thenRequest(1)
                    .expectComplete()
                    .verify();

        await().until(() -> httpRequest.completionFuture().isDone());
    }

    @Test
    public void writeAndFlushWithMultiplePublisher() {
        final ArmeriaClientHttpRequest request = request();
        final Flux<Flux<DataBuffer>> body = Flux.just(
                Flux.just("a", "b", "c", "d", "e").map(String::getBytes)
                    .map(DataBufferFactoryWrapper.DEFAULT.delegate()::wrap),
                Flux.just("1", "2", "3", "4", "5").map(String::getBytes)
                    .map(DataBufferFactoryWrapper.DEFAULT.delegate()::wrap)
        );

        request.writeAndFlushWith(body)
               .then(Mono.defer(request::setComplete))
               .subscribe();

        // Wait until calling HttpClient#execute.
        await().until(() -> request.future().isDone());

        // Consume from Armeria HttpRequest.
        final HttpRequest httpRequest = request.request();
        assertThat(httpRequest).isNotNull();

        // Check the headers.
        final RequestHeaders headers = httpRequest.headers();
        assertThat(headers.method()).isEqualTo(com.linecorp.armeria.common.HttpMethod.GET);
        assertThat(headers.path()).isEqualTo(TEST_PATH_AND_QUERY);
        assertThat(headers.get(HttpHeaderNames.ACCEPT)).isEqualTo("*/*");

        // Armeria HttpRequest produces http body only.
        final Flux<String> requestBody =
                Flux.from(httpRequest).cast(HttpData.class).map(HttpData::toStringUtf8);
        StepVerifier.create(requestBody, 1)
                    .expectNext("a").thenRequest(1)
                    .expectNext("b").thenRequest(1)
                    .expectNext("c").thenRequest(1)
                    .expectNext("d").thenRequest(1)
                    .expectNext("e").thenRequest(1)
                    .expectNext("1").thenRequest(1)
                    .expectNext("2").thenRequest(1)
                    .expectNext("3").thenRequest(1)
                    .expectNext("4").thenRequest(1)
                    .expectNext("5").thenRequest(1)
                    .expectComplete()
                    .verify();

        await().until(() -> httpRequest.completionFuture().isDone());
    }
}
