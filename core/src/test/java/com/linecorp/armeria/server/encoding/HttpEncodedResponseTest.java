/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server.encoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.encoding.StreamDecoder;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.internal.common.encoding.StreamEncoderFactories;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.ImmediateEventExecutor;
import reactor.test.StepVerifier;

class HttpEncodedResponseTest {

    @Test
    void testLeak() {
        final ByteBuf buf = Unpooled.directBuffer();
        buf.writeCharSequence("foo", StandardCharsets.UTF_8);

        final HttpResponse orig =
                AggregatedHttpResponse.of(HttpStatus.OK,
                                          MediaType.PLAIN_TEXT_UTF_8,
                                          HttpData.wrap(buf).withEndOfStream()).toHttpResponse();
        final HttpEncodedResponse encoded = new HttpEncodedResponse(
                orig, StreamEncoderFactories.DEFLATE, mediaType -> true, ByteBufAllocator.DEFAULT, 1);

        // Drain the stream.
        encoded.subscribe(NoopSubscriber.get(), ImmediateEventExecutor.INSTANCE);

        // 'buf' should be released.
        assertThat(buf.refCnt()).isZero();
    }

    @Test
    void shouldReleaseEncodedStreamOnCancel() {
        final HttpResponse orig =
                HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                HttpData.ofUtf8("foo"),
                                HttpData.ofUtf8("bar"),
                                HttpData.ofUtf8("baz"));
        final HttpEncodedResponse encoded = new HttpEncodedResponse(
                orig, StreamEncoderFactories.DEFLATE, mediaType -> true, ByteBufAllocator.DEFAULT, 1);

        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        // Drain the stream.
        encoded.subscribe(new Subscriber<HttpObject>() {

            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                if (httpObject instanceof HttpData) {
                    assertThat(((HttpData) httpObject).isEmpty()).isFalse();
                    subscription.cancel();
                } else {
                    subscription.request(1);
                }
            }

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {
            }
        }, ImmediateEventExecutor.INSTANCE, SubscriptionOption.NOTIFY_CANCELLATION);

        await().untilAsserted(() -> {
            assertThat(causeRef.get()).isInstanceOf(CancelledSubscriptionException.class);
            assertThatThrownBy(() -> encoded.whenComplete().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(CancelledSubscriptionException.class);
            assertThat(encoded.encodedStream.buffer().refCnt()).isZero();
        });
    }

    @Test
    void shouldReleaseEncodedStreamOnError() {
        final HttpResponse orig =
                HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                HttpData.ofUtf8("foo"),
                                HttpData.ofUtf8("bar"),
                                HttpData.ofUtf8("baz"));
        final HttpEncodedResponse encoded = new HttpEncodedResponse(
                orig, StreamEncoderFactories.BROTLI, mediaType -> true, ByteBufAllocator.DEFAULT, 1);

        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        encoded.subscribe(new Subscriber<HttpObject>() {

            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                if (httpObject instanceof HttpData) {
                    assertThat(((HttpData) httpObject).isEmpty()).isFalse();
                    encoded.abort();
                } else {
                    subscription.request(1);
                }
            }

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {}
        }, ImmediateEventExecutor.INSTANCE, SubscriptionOption.NOTIFY_CANCELLATION);

        await().untilAsserted(() -> {
            assertThat(causeRef.get()).isInstanceOf(AbortedStreamException.class);
            assertThatThrownBy(() -> encoded.whenComplete().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(AbortedStreamException.class);
            assertThat(encoded.encodedStream.buffer().refCnt()).isZero();
        });
    }

    @Test
    void doNotEncodeWhenContentShouldBeEmpty() {
        final ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.NO_CONTENT).contentType(
                MediaType.PLAIN_TEXT_UTF_8).build();
        // Add CONTINUE not to validate when creating HttpResponse.
        final HttpResponse orig = HttpResponse.of(ResponseHeaders.of(HttpStatus.CONTINUE), headers,
                                                  HttpData.ofUtf8("foo"));
        final HttpEncodedResponse encoded = new HttpEncodedResponse(
                orig, StreamEncoderFactories.DEFLATE, mediaType -> true, ByteBufAllocator.DEFAULT, 1);
        StepVerifier.create(encoded)
                    .expectNext(ResponseHeaders.of(HttpStatus.CONTINUE))
                    .expectNext(headers)
                    .expectNext(HttpData.ofUtf8("foo"))
                    .expectComplete()
                    .verify();
    }

    @Test
    void shouldEncodeContent() {
        final HttpResponse orig =
                HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                                HttpData.ofUtf8("foo"),
                                HttpData.ofUtf8("bar"),
                                HttpData.ofUtf8("baz"));
        final HttpEncodedResponse encoded = new HttpEncodedResponse(
                orig, StreamEncoderFactories.DEFLATE, mediaType -> true, ByteBufAllocator.DEFAULT, 1);
        final List<HttpData> data = encoded.split().body().collect().join();
        final StreamDecoder decoder = StreamDecoderFactory.deflate().newDecoder(ByteBufAllocator.DEFAULT);

        String result = data.stream().map(encodedData -> {
                                try (HttpData httpData = decoder.decode(encodedData)) {
                                    return httpData.toStringUtf8();
                                }
                            })
                            .collect(Collectors.joining());
        final HttpData finish = decoder.finish();
        result += finish.toStringUtf8();
        finish.close();
        assertThat(result).isEqualTo("foobarbaz");
        assertThat(encoded.encodedStream.buffer().refCnt()).isZero();
    }
}
