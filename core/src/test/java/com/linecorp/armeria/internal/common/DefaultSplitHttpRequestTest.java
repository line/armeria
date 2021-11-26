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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.instanceOf;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SplitHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class DefaultSplitHttpRequestTest {

    @Test
    void headers() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final SplitHttpRequest splitHttpRequest = request.split();
        final RequestHeaders headers = splitHttpRequest.headers();
        assertThat(headers.method()).isEqualTo(HttpMethod.GET);
        assertThat(headers.path()).isEqualTo("/");
    }

    @Test
    void emptyBody() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/");
        final SplitHttpRequest splitHttpRequest = request.split();
        StepVerifier.create(splitHttpRequest.body())
                    .thenRequest(1)
                    .expectNextCount(0)
                    .verifyComplete();
    }

    @Test
    void body() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                   HttpData.ofUtf8("Hello1"),
                                                   HttpData.ofUtf8("Hello2"));
        final SplitHttpRequest splitHttpRequest = request.split();
        StepVerifier.create(splitHttpRequest.body())
                    .thenRequest(2)
                    .expectNextCount(2)
                    .verifyComplete();
    }

    @Test
    void trailers() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                   HttpData.ofUtf8("Hello"),
                                                   HttpHeaders.of("grpc-status", "0"));
        final SplitHttpRequest splitHttpRequest = request.split();
        final CompletableFuture<HttpHeaders> trailersFuture = splitHttpRequest.trailers();
        assertThat(trailersFuture).isNotDone();
        StepVerifier.create(splitHttpRequest.body())
                    .thenRequest(1)
                    .expectNextCount(1)
                    .verifyComplete();
        assertThat(trailersFuture.join().get("grpc-status")).isEqualTo("0");
    }

    @Test
    void emptyTrailers() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                   HttpData.ofUtf8("Hello"));
        final SplitHttpRequest splitHttpRequest = request.split();
        final CompletableFuture<HttpHeaders> trailersFuture = splitHttpRequest.trailers();
        assertThat(trailersFuture).isNotDone();
        StepVerifier.create(splitHttpRequest.body())
                    .thenRequest(1)
                    .expectNextCount(1)
                    .verifyComplete();
        assertThat(trailersFuture.join()).isEqualTo(HttpHeaders.of());
    }

    @Test
    void publisherBasedRequest() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                   Flux.just(HttpData.ofUtf8("Hello1"),
                                                             HttpData.ofUtf8("Hello2"),
                                                             HttpHeaders.of("grpc-status", "0")));
        final SplitHttpRequest splitHttpRequest = request.split();
        StepVerifier.create(splitHttpRequest.body())
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("Hello1"))
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("Hello2"))
                    .verifyComplete();
        assertThat(splitHttpRequest.trailers().join().get("grpc-status")).isEqualTo("0");
    }

    @Test
    void abortedRequest() {
        final HttpRequestWriter streamingRequest = HttpRequest.streaming(HttpMethod.GET, "/");
        streamingRequest.abort();

        final SplitHttpRequest splitHttpRequest = streamingRequest.split();
        StepVerifier.create(splitHttpRequest.body())
                    .thenRequest(1)
                    .expectError(AbortedStreamException.class)
                    .verify();
        assertThat(splitHttpRequest.headers().method()).isEqualTo(HttpMethod.GET);
        assertThat(splitHttpRequest.trailers().join().isEmpty()).isTrue();
    }

    @Test
    void cancelRequest() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                   Flux.just(HttpData.ofUtf8("Hello1"),
                                                             HttpData.ofUtf8("Hello2"),
                                                             HttpHeaders.of("grpc-status", "0")));
        final SplitHttpRequest splitHttpRequest = request.split();
        StepVerifier.create(splitHttpRequest.body())
                    .thenCancel()
                    .verify();
        assertThat(splitHttpRequest.trailers().join().isEmpty()).isTrue();
    }

    @Test
    void cancelNotification() {
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                   Flux.just(HttpData.ofUtf8("Hello1"),
                                                             HttpData.ofUtf8("Hello2"),
                                                             HttpHeaders.of("grpc-status", "0")));
        final SplitHttpRequest splitHttpRequest = request.split();
        final StreamMessage<HttpData> body = splitHttpRequest.body();
        final AtomicReference<Throwable> causeCaptor = new AtomicReference<>();

        body.subscribe(new Subscriber<HttpData>() {

            @Nullable
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                subscription.request(1);
            }

            @Override
            public void onNext(HttpData httpData) {
                subscription.cancel();
            }

            @Override
            public void onError(Throwable t) {
                causeCaptor.set(t);
            }

            @Override
            public void onComplete() {
            }
        }, SubscriptionOption.NOTIFY_CANCELLATION);

        await().untilAtomic(causeCaptor, instanceOf(CancelledSubscriptionException.class));
    }

    @Test
    void pooledObjects() {
        final AtomicReference<HttpData> httpDataRef = new AtomicReference<>();
        final ByteBuf buf = Unpooled.buffer(4).writeInt(0x01020304);
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"), HttpData.wrap(buf));

        request.split().body().subscribe(new Subscriber<HttpData>() {

            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                httpDataRef.set(httpData);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        }, SubscriptionOption.WITH_POOLED_OBJECTS);

        await().untilAsserted(() -> {
            assertThat(httpDataRef.get().isPooled()).isTrue();
            assertThat(httpDataRef.get().byteBuf()).isEqualTo(buf);
            assertThat(httpDataRef.get().byteBuf().refCnt()).isOne();
        });
        buf.release();
    }

    @Test
    void heapObjects() {
        final AtomicReference<HttpData> httpDataRef = new AtomicReference<>();
        final HttpRequest request = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                                   HttpData.ofUtf8("Hello"));

        request.split().body().subscribe(new Subscriber<HttpData>() {

            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                httpDataRef.set(httpData);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        }, SubscriptionOption.WITH_POOLED_OBJECTS);

        await().untilAsserted(() -> {
            assertThat(httpDataRef.get().isPooled()).isFalse();
            assertThat(httpDataRef.get().toStringUtf8()).isEqualTo("Hello");
        });
    }
}
