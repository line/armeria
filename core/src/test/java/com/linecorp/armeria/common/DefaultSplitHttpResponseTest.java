/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.instanceOf;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class DefaultSplitHttpResponseTest {

    @Test
    void emptyBody() {
        final HttpResponse response = HttpResponse.of(HttpStatus.NO_CONTENT);
        final SplitHttpResponse splitHttpResponse = response.split();
        StepVerifier.create(splitHttpResponse.body())
                    .thenRequest(1)
                    .expectNextCount(0)
                    .verifyComplete();
        assertThat(splitHttpResponse.headers().join().status()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void completeHeadersBeforeConsumeBody() {
        final HttpResponse response = HttpResponse.of(HttpStatus.OK);
        final SplitHttpResponse bodyStream = response.split();
        assertThat(bodyStream.headers().join().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void dropInformationalHeaders() {
        final HttpResponse response = HttpResponse.of(ResponseHeaders.of(HttpStatus.CONTINUE),
                                                      ResponseHeaders.of(HttpStatus.PROCESSING),
                                                      ResponseHeaders.of(HttpStatus.OK),
                                                      HttpData.ofUtf8("Hello"));
        final SplitHttpResponse splitHttpResponse = response.split();

        StepVerifier.create(splitHttpResponse.body())
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("Hello"))
                    .verifyComplete();
        assertThat(splitHttpResponse.headers().join().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void trailers() {
        final HttpResponse response = HttpResponse.of(Flux.just(ResponseHeaders.of(HttpStatus.OK),
                                                                HttpData.ofUtf8("Hello1"),
                                                                HttpData.ofUtf8("Hello2"),
                                                                HttpHeaders.of("grpc-status", "0")));
        final SplitHttpResponse splitHttpResponse = response.split();

        assertThat(splitHttpResponse.trailers()).isNotDone();
        StepVerifier.create(splitHttpResponse.body())
                    .thenRequest(2)
                    .expectNextCount(2)
                    .verifyComplete();
        assertThat(splitHttpResponse.trailers().join().get("grpc-status")).isEqualTo("0");
    }

    @Test
    void publisherBasedResponse() {
        final HttpResponse response = HttpResponse.of(Flux.just(ResponseHeaders.of(HttpStatus.OK),
                                                                HttpData.ofUtf8("Hello1"),
                                                                HttpData.ofUtf8("Hello2")));

        final SplitHttpResponse splitHttpResponse = response.split();
        StepVerifier.create(splitHttpResponse.body())
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("Hello1"))
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("Hello2"))
                    .verifyComplete();
    }

    @Test
    void failedResponse() {
        final HttpResponse response = HttpResponse.ofFailure(ResponseTimeoutException.get());
        final SplitHttpResponse splitHttpResponse = response.split();
        StepVerifier.create(splitHttpResponse.body())
                    .thenRequest(1)
                    .expectError(ResponseTimeoutException.class)
                    .verify();
        assertThatThrownBy(() -> splitHttpResponse.headers().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ResponseTimeoutException.class);
        assertThat(splitHttpResponse.trailers().join().isEmpty()).isTrue();
    }

    @Test
    void abortedResponse() {
        final HttpResponse response = HttpResponse.ofFailure(AbortedStreamException.get());
        final SplitHttpResponse splitHttpResponse = response.split();
        StepVerifier.create(splitHttpResponse.body())
                    .thenRequest(1)
                    .expectError(AbortedStreamException.class)
                    .verify();
        assertThat(splitHttpResponse.headers().join().status()).isEqualTo(HttpStatus.UNKNOWN);
        assertThat(splitHttpResponse.trailers().join().isEmpty()).isTrue();
    }

    @Test
    void cancelResponse() {
        final HttpResponse response = HttpResponse.of(Flux.just(ResponseHeaders.of(HttpStatus.OK),
                                                                HttpData.ofUtf8("Hello1"),
                                                                HttpData.ofUtf8("Hello2"),
                                                                HttpHeaders.of("grpc-status", 0)));
        final SplitHttpResponse splitHttpResponse = response.split();
        // HTTP headers is prefetched before subscribing to HTTP body.
        assertThat(splitHttpResponse.headers().join()).isEqualTo(ResponseHeaders.of(HttpStatus.OK));
        StepVerifier.create(splitHttpResponse.body())
                    .thenCancel()
                    .verify();

        assertThat(splitHttpResponse.trailers().join().isEmpty()).isTrue();
    }

    @Test
    void cancelNotification() {
        final HttpResponse response = HttpResponse.of(Flux.just(ResponseHeaders.of(HttpStatus.OK),
                                                                HttpData.ofUtf8("Hello1"),
                                                                HttpData.ofUtf8("Hello2"),
                                                                HttpHeaders.of("grpc-status", 0)));
        final SplitHttpResponse splitHttpResponse = response.split();
        final StreamMessage<HttpData> body = splitHttpResponse.body();
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
            public void onNext(HttpData data) {
                subscription.cancel();
            }

            @Override
            public void onError(Throwable t) {
                causeCaptor.set(t);
            }

            @Override
            public void onComplete() {}
        }, SubscriptionOption.NOTIFY_CANCELLATION);

        await().untilAtomic(causeCaptor, instanceOf(CancelledSubscriptionException.class));
    }

    @Test
    void pooledObjects() {
        final AtomicReference<HttpData> httpDataRef = new AtomicReference<>();
        final ByteBuf buf = Unpooled.buffer(4).writeInt(0x01020304);
        final HttpResponse response = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK), HttpData.wrap(buf));

        response.split().body().subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                httpDataRef.set(httpData);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
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
        final HttpResponse response = HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                                      HttpData.ofUtf8("ABC"));

        response.split().body().subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                httpDataRef.set(httpData);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        }, SubscriptionOption.WITH_POOLED_OBJECTS);

        await().untilAsserted(() -> {
            assertThat(httpDataRef.get().isPooled()).isFalse();
            assertThat(httpDataRef.get().toStringUtf8()).isEqualTo("ABC");
        });
    }
}
