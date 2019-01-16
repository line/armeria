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

import static com.linecorp.armeria.spring.web.reactive.TestUtil.ensureHttpDataOfString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class ArmeriaServerHttpResponseTest {

    static final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    private static ArmeriaServerHttpResponse response(
            ServiceRequestContext ctx, CompletableFuture<HttpResponse> future) {
        return new ArmeriaServerHttpResponse(ctx, future, DataBufferFactoryWrapper.DEFAULT, null);
    }

    @Test
    public void returnHeadersOnly() throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response = response(ctx, future);

        response.setStatusCode(HttpStatus.NOT_FOUND);
        assertThat(future.isDone()).isFalse();

        // Create HttpResponse.
        response.setComplete().subscribe();

        await().until(future::isDone);
        assertThat(future.isCompletedExceptionally()).isFalse();

        final HttpResponse httpResponse = future.get();

        // Every message has not been consumed yet.
        assertThat(httpResponse.completionFuture().isDone()).isFalse();

        StepVerifier.create(httpResponse)
                    .assertNext(o -> {
                        assertThat(o.isEndOfStream()).isFalse();
                        assertThat(o).isInstanceOf(HttpHeaders.class);
                        final HttpHeaders headers = (HttpHeaders) o;
                        assertThat(headers.status())
                                .isEqualTo(com.linecorp.armeria.common.HttpStatus.NOT_FOUND);
                    })
                    .expectComplete()
                    .verify();

        await().until(() -> httpResponse.completionFuture().isDone());
    }

    @Test
    public void returnHeadersAndBody() throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response = response(ctx, future);

        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().add("Armeria", "awesome");
        response.addCookie(ResponseCookie.from("a", "1")
                                         .domain("http://localhost")
                                         .path("/")
                                         .maxAge(Duration.ofSeconds(60))
                                         .secure(true)
                                         .httpOnly(true)
                                         .build());
        assertThat(future.isDone()).isFalse();

        final Flux<DataBuffer> body = Flux.just("a", "b", "c", "d", "e")
                                          .map(String::getBytes)
                                          .map(DataBufferFactoryWrapper.DEFAULT.delegate()::wrap);

        // Create HttpResponse.
        response.writeWith(body).then(Mono.defer(response::setComplete)).subscribe();

        await().until(future::isDone);
        assertThat(future.isCompletedExceptionally()).isFalse();

        final HttpResponse httpResponse = future.get();

        // Every message has not been consumed yet.
        assertThat(httpResponse.completionFuture().isDone()).isFalse();

        StepVerifier.create(httpResponse)
                    .assertNext(o -> {
                        assertThat(o.isEndOfStream()).isFalse();
                        assertThat(o).isInstanceOf(HttpHeaders.class);
                        final HttpHeaders headers = (HttpHeaders) o;
                        assertThat(headers.status())
                                .isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);
                        assertThat(headers.get(HttpHeaderNames.of("Armeria"))).isEqualTo("awesome");
                        final Cookie setCookie =
                                ClientCookieDecoder.LAX.decode(headers.get(HttpHeaderNames.SET_COOKIE));
                        assertThat(setCookie.name()).isEqualTo("a");
                        assertThat(setCookie.value()).isEqualTo("1");
                        assertThat(setCookie.domain()).isEqualTo("http://localhost");
                        assertThat(setCookie.path()).isEqualTo("/");
                        assertThat(setCookie.maxAge()).isEqualTo(Duration.ofSeconds(60).getSeconds());
                        assertThat(setCookie.isSecure()).isTrue();
                        assertThat(setCookie.isHttpOnly()).isTrue();
                    })
                    .assertNext(o -> ensureHttpDataOfString(o, "a"))
                    .assertNext(o -> ensureHttpDataOfString(o, "b"))
                    .assertNext(o -> ensureHttpDataOfString(o, "c"))
                    .assertNext(o -> ensureHttpDataOfString(o, "d"))
                    .assertNext(o -> ensureHttpDataOfString(o, "e"))
                    .expectComplete()
                    .verify();

        await().until(() -> httpResponse.completionFuture().isDone());
    }

    @Test
    public void controlBackpressure() throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response = response(ctx, future);

        response.setStatusCode(HttpStatus.OK);
        assertThat(future.isDone()).isFalse();

        final Flux<DataBuffer> body = Flux.just("a", "b", "c", "d", "e", "f", "g")
                                          .map(String::getBytes)
                                          .map(DataBufferFactoryWrapper.DEFAULT.delegate()::wrap);

        // Create HttpResponse.
        response.writeWith(body).then(Mono.defer(response::setComplete)).subscribe();

        await().until(future::isDone);
        assertThat(future.isCompletedExceptionally()).isFalse();

        final HttpResponse httpResponse = future.get();

        // Every message has not been consumed yet.
        assertThat(httpResponse.completionFuture().isDone()).isFalse();

        StepVerifier.create(httpResponse, 1)
                    .assertNext(o -> {
                        assertThat(o.isEndOfStream()).isFalse();
                        assertThat(o).isInstanceOf(HttpHeaders.class);
                    })
                    .thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "a"))
                    .thenRequest(3)
                    .assertNext(o -> ensureHttpDataOfString(o, "b"))
                    .assertNext(o -> ensureHttpDataOfString(o, "c"))
                    .assertNext(o -> ensureHttpDataOfString(o, "d"))
                    .thenRequest(2)
                    .assertNext(o -> ensureHttpDataOfString(o, "e"))
                    .assertNext(o -> ensureHttpDataOfString(o, "f"))
                    .thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "g"))
                    .thenRequest(1)
                    .expectComplete()
                    .verify();

        await().until(() -> httpResponse.completionFuture().isDone());
    }

    @Test
    public void returnHeadersAndBodyWithMultiplePublisher() throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response = response(ctx, future);

        response.setStatusCode(HttpStatus.OK);
        assertThat(future.isDone()).isFalse();

        final Flux<Flux<DataBuffer>> body = Flux.just(
                Flux.just("a", "b", "c", "d", "e").map(String::getBytes)
                    .map(DataBufferFactoryWrapper.DEFAULT.delegate()::wrap),
                Flux.just("1", "2", "3", "4", "5").map(String::getBytes)
                    .map(DataBufferFactoryWrapper.DEFAULT.delegate()::wrap)
        );

        // Create HttpResponse.
        response.writeAndFlushWith(body).then(Mono.defer(response::setComplete)).subscribe();

        await().until(future::isDone);
        assertThat(future.isCompletedExceptionally()).isFalse();

        final HttpResponse httpResponse = future.get();

        // Every message has not been consumed yet.
        assertThat(httpResponse.completionFuture().isDone()).isFalse();

        StepVerifier.create(httpResponse, 1)
                    .assertNext(o -> {
                        assertThat(o.isEndOfStream()).isFalse();
                        assertThat(o).isInstanceOf(HttpHeaders.class);
                    })
                    .thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "a")).thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "b")).thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "c")).thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "d")).thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "e")).thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "1")).thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "2")).thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "3")).thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "4")).thenRequest(1)
                    .assertNext(o -> ensureHttpDataOfString(o, "5")).thenRequest(1)
                    .expectComplete()
                    .verify();

        await().until(() -> httpResponse.completionFuture().isDone());
    }
}
