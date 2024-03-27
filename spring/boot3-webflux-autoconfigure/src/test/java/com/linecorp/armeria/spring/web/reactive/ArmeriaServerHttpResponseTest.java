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
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.spring.internal.common.DataBufferFactoryWrapper;

import io.netty.buffer.PooledByteBufAllocator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ArmeriaServerHttpResponseTest {

    static final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    private static ArmeriaServerHttpResponse response(
            ServiceRequestContext ctx, CompletableFuture<HttpResponse> future) {
        return new ArmeriaServerHttpResponse(ctx, future, DataBufferFactoryWrapper.DEFAULT, null);
    }

    @Test
    void returnHeadersOnly() throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response = response(ctx, future);

        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.addCookie(ResponseCookie.from("a", "1")
                                         .domain("localhost")
                                         .path("/")
                                         // A negative value means no "Max-Age" attribute in which case
                                         // the cookie is removed when the browser is closed.
                                         .maxAge(-1)
                                         .secure(true)
                                         .httpOnly(true)
                                         .build());
        assertThat(future).isNotDone();

        // Create HttpResponse.
        response.setComplete().subscribe();

        await().until(future::isDone);
        assertThat(future.isCompletedExceptionally()).isFalse();

        final HttpResponse httpResponse = future.get();

        // Every message has not been consumed yet.
        assertThat(httpResponse.whenComplete()).isNotDone();

        StepVerifier.create(httpResponse)
                    .assertNext(o -> {
                        assertThat(o.isEndOfStream()).isFalse();
                        assertThat(o).isInstanceOf(ResponseHeaders.class);
                        final ResponseHeaders headers = (ResponseHeaders) o;
                        assertThat(headers.status().code()).isEqualTo(404);
                        final String setCookieValue = headers.get(HttpHeaderNames.SET_COOKIE);
                        assertThat(setCookieValue).isNotNull();
                        final Cookie setCookie = Cookie.fromSetCookieHeader(setCookieValue);
                        assertThat(setCookie).isNotNull();
                        assertThat(setCookie.name()).isEqualTo("a");
                        assertThat(setCookie.value()).isEqualTo("1");
                        assertThat(setCookie.domain()).isEqualTo("localhost");
                        assertThat(setCookie.path()).isEqualTo("/");
                        assertThat(setCookie.maxAge()).isEqualTo(Cookie.UNDEFINED_MAX_AGE);
                        assertThat(setCookie.isSecure()).isTrue();
                        assertThat(setCookie.isHttpOnly()).isTrue();
                    })
                    .expectComplete()
                    .verify();

        await().until(() -> httpResponse.whenComplete().isDone());

        // Spring headers does not have pseudo headers.
        for (Entry<String, List<String>> header : response.getHeaders().entrySet()) {
            assertThat(header.getKey()).doesNotStartWith(":");
        }
    }

    @Test
    void returnHeadersAndBody() throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response = response(ctx, future);

        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().add("Armeria", "awesome");
        response.addCookie(ResponseCookie.from("a", "1")
                                         .domain("localhost")
                                         .path("/")
                                         .maxAge(Duration.ofSeconds(60))
                                         .secure(true)
                                         .httpOnly(true)
                                         .build());
        assertThat(future).isNotDone();

        final Flux<DataBuffer> body = Flux.just("a", "b", "c", "d", "e")
                                          .map(String::getBytes)
                                          .map(DataBufferFactoryWrapper.DEFAULT.delegate()::wrap);

        // Create HttpResponse.
        response.writeWith(body).then(Mono.defer(response::setComplete)).subscribe();

        await().until(future::isDone);
        assertThat(future.isCompletedExceptionally()).isFalse();

        final HttpResponse httpResponse = future.get();

        // Every message has not been consumed yet.
        assertThat(httpResponse.whenComplete()).isNotDone();

        StepVerifier.create(httpResponse)
                    .assertNext(o -> {
                        assertThat(o.isEndOfStream()).isFalse();
                        assertThat(o).isInstanceOf(ResponseHeaders.class);
                        final ResponseHeaders headers = (ResponseHeaders) o;
                        assertThat(headers.status().code()).isEqualTo(200);
                        assertThat(headers.get(HttpHeaderNames.of("Armeria"))).isEqualTo("awesome");
                        final String setCookieValue = headers.get(HttpHeaderNames.SET_COOKIE);
                        assertThat(setCookieValue).isNotNull();
                        final Cookie setCookie = Cookie.fromSetCookieHeader(setCookieValue);
                        assertThat(setCookie).isNotNull();
                        assertThat(setCookie.name()).isEqualTo("a");
                        assertThat(setCookie.value()).isEqualTo("1");
                        assertThat(setCookie.domain()).isEqualTo("localhost");
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

        await().until(() -> httpResponse.whenComplete().isDone());
    }

    @Test
    void ignoreCancelledSubscriptionException() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.HEAD, "/"));

        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response = response(ctx, future);

        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().add("Armeria", "awesome");
        assertThat(future).isNotDone();

        StepVerifier.create(Mono.defer(response::setComplete))
                    .then(() -> {
                        try {
                            // throw CancelledSubscriptionException as HttpResponseSubscriber
                            // cancels subscription for HTTP HEAD
                            final HttpResponse httpResponse = future.get();
                            httpResponse.whenComplete()
                                        .completeExceptionally(CancelledSubscriptionException.get());
                        } catch (Throwable ignored) {
                        }
                    })
                    .verifyComplete();
    }

    @Test
    void controlBackpressure() throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response = response(ctx, future);

        response.setStatusCode(HttpStatus.OK);
        assertThat(future).isNotDone();

        final Flux<DataBuffer> body = Flux.just("a", "b", "c", "d", "e", "f", "g")
                                          .map(String::getBytes)
                                          .map(DataBufferFactoryWrapper.DEFAULT.delegate()::wrap);

        // Create HttpResponse.
        response.writeWith(body).then(Mono.defer(response::setComplete)).subscribe();

        await().until(future::isDone);
        assertThat(future.isCompletedExceptionally()).isFalse();

        final HttpResponse httpResponse = future.get();

        // Every message has not been consumed yet.
        assertThat(httpResponse.whenComplete()).isNotDone();

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

        await().until(() -> httpResponse.whenComplete().isDone());
    }

    @Test
    void returnHeadersAndBodyWithMultiplePublisher() throws Exception {
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response = response(ctx, future);

        response.setStatusCode(HttpStatus.OK);
        assertThat(future).isNotDone();

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
        assertThat(httpResponse.whenComplete()).isNotDone();

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

        await().until(() -> httpResponse.whenComplete().isDone());
    }

    @Test
    void requestInvalidDemand() throws Exception {
        final ConcurrentLinkedQueue<NettyDataBuffer> allocatedBuffers = new ConcurrentLinkedQueue<>();
        final DataBufferFactoryWrapper<NettyDataBufferFactory> factoryWrapper = new DataBufferFactoryWrapper<>(
                new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT) {
                    @Override
                    public NettyDataBuffer allocateBuffer(int initialCapacity) {
                        final NettyDataBuffer buffer = super.allocateBuffer(initialCapacity);
                        allocatedBuffers.offer(buffer);
                        return buffer;
                    }
                });
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        final ArmeriaServerHttpResponse response =
                new ArmeriaServerHttpResponse(ctx, future, factoryWrapper, null);
        response.writeWith(Mono.just(factoryWrapper.delegate().allocateBuffer(3).write("foo".getBytes())))
                .then(Mono.defer(response::setComplete)).subscribe();
        await().until(future::isDone);
        assertThat(future.isCompletedExceptionally()).isFalse();

        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        future.get().subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(0);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                // Do nothing.
            }

            @Override
            public void onError(Throwable t) {
                error.compareAndSet(null, t);
                completed.set(true);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        await().untilTrue(completed);
        assertThat(error.get()).isInstanceOf(IllegalArgumentException.class)
                               .hasMessageContaining("non-positive request signals are illegal");
        await().untilAsserted(() -> {
            assertThat(allocatedBuffers).hasSize(1);
            assertThat(allocatedBuffers.peek().getNativeBuffer().refCnt()).isZero();
            allocatedBuffers.poll();
        });
    }
}
