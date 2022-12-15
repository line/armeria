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

package com.linecorp.armeria.server.encoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.encoding.StreamDecoder;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.CompositeException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import reactor.test.StepVerifier;

class HttpDecodedRequestTest {

    private static final StreamDecoderFactory DECODER = StreamDecoderFactory.gzip();

    private static final RequestHeaders REQUEST_HEADERS =
            RequestHeaders.of(HttpMethod.POST, "/", HttpHeaderNames.CONTENT_ENCODING, "gzip");

    private static final byte[] PAYLOAD;

    static {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write("hello".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        PAYLOAD = bos.toByteArray();
    }

    @Test
    void unpooledPayload_unpooledDrain() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpRequest delegate = HttpRequest.of(REQUEST_HEADERS, payload);
        final HttpRequest decoded = new HttpDecodedRequest(delegate, DECODER, ByteBufAllocator.DEFAULT,
                                                           0);
        final HttpData decodedPayload = requestData(decoded, false);

        assertThat(decodedPayload.isPooled()).isFalse();
    }

    @Test
    void pooledPayload_unpooledDrain() {
        final ByteBuf payloadBuf = ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD);
        final HttpData payload = HttpData.wrap(payloadBuf).withEndOfStream();
        final HttpRequest delegate = HttpRequest.of(REQUEST_HEADERS, payload);
        final HttpRequest decoded = new HttpDecodedRequest(delegate, DECODER, ByteBufAllocator.DEFAULT,
                                                           0);
        final HttpData decodedPayload = requestData(decoded, false);

        assertThat(decodedPayload.isPooled()).isFalse();
        assertThat(payloadBuf.refCnt()).isZero();
    }

    @Test
    void unpooledPayload_pooledDrain() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpRequest delegate = HttpRequest.of(REQUEST_HEADERS, payload);
        final HttpRequest decoded = new HttpDecodedRequest(delegate, DECODER, ByteBufAllocator.DEFAULT,
                                                           0);
        final HttpData decodedPayload = requestData(decoded, true);

        assertThat(decodedPayload.isPooled()).isTrue();
        assertThat(decodedPayload.byteBuf().refCnt()).isOne();
        decodedPayload.close();
    }

    @Test
    void pooledPayload_pooledDrain() {
        final ByteBuf payloadBuf = ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD);
        final HttpData payload = HttpData.wrap(payloadBuf).withEndOfStream();
        final HttpRequest delegate = HttpRequest.of(REQUEST_HEADERS, payload);
        final HttpRequest decoded = new HttpDecodedRequest(delegate, DECODER, ByteBufAllocator.DEFAULT,
                                                           0);
        final HttpData decodedPayload = requestData(decoded, true);
        final ByteBuf decodedPayloadBuf = decodedPayload.byteBuf();

        assertThat(payloadBuf.refCnt()).isZero();
        assertThat(decodedPayload.isPooled()).isTrue();
        decodedPayload.close();
        assertThat(decodedPayloadBuf.refCnt()).isZero();
    }

    @Test
    void streamDecoderFinishedIsCalledWhenRequestCanceled() throws InterruptedException {
        final HttpRequestWriter request = HttpRequest.streaming(REQUEST_HEADERS);
        final HttpData data = HttpData.ofUtf8("foo");
        request.write(data);

        final StreamDecoderFactory factory = mock(StreamDecoderFactory.class);
        final StreamDecoder streamDecoder = mock(StreamDecoder.class);
        when(factory.newDecoder(any(), eq(0))).thenReturn(streamDecoder);
        when(streamDecoder.decode(any())).thenReturn(data);

        final HttpRequest decoded = new HttpDecodedRequest(request, factory, ByteBufAllocator.DEFAULT,
                                                           0);
        decoded.subscribe(new CancelSubscriber());

        await().untilAsserted(() -> verify(streamDecoder, times(1)).finish());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void shouldHandleExceptionInDecoderFinishOnComplete(boolean aggregation) {
        final HttpRequest decoded = newFailingDecodedRequest();
        if (aggregation) {
            assertThatThrownBy(() -> decoded.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ContentTooLargeException.class);
        } else {
            StepVerifier.create(decoded)
                        .expectNext(HttpData.ofUtf8("Hello"))
                        .expectError(ContentTooLargeException.class)
                        .verify();
        }
        assertThatThrownBy(() -> decoded.whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ContentTooLargeException.class);
    }

    @Test
    void shouldHandleExceptionInDecoderFinishOnError() {
        final HttpRequest decoded = newFailingDecodedRequest();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        decoded.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                decoded.abort();
            }

            @Override
            public void onNext(HttpObject httpObject) {}

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {}
        });
        await().untilAsserted(() -> {
            assertThat(causeRef.get())
                    .isInstanceOf(CompositeException.class)
                    .satisfies(cause -> {
                        final CompositeException compositeException = (CompositeException) cause;
                        assertThat(compositeException.getExceptions()).hasSize(2);
                        assertThat(compositeException.getExceptions().get(0))
                                .isInstanceOf(AbortedStreamException.class);

                        assertThat(compositeException.getExceptions().get(1))
                                .isInstanceOf(ContentTooLargeException.class);
                    });
        });
        assertThatThrownBy(() -> decoded.whenComplete().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CompositeException.class);
    }

    private static HttpRequest newFailingDecodedRequest() {
        final HttpRequest request = HttpRequest.of(REQUEST_HEADERS, HttpData.ofUtf8("Hello"));

        final StreamDecoderFactory decoderFactory = new StreamDecoderFactory() {

            @Override
            public String encodingHeaderValue() {
                return "gzip";
            }

            @Override
            public StreamDecoder newDecoder(ByteBufAllocator alloc, int maxLength) {
                return new StreamDecoder() {
                    @Override
                    public HttpData decode(HttpData obj) {
                        return HttpData.ofUtf8("Hello");
                    }

                    @Override
                    public HttpData finish() {
                        throw ContentTooLargeException.get();
                    }

                    @Override
                    public int maxLength() {
                        return maxLength;
                    }
                };
            }
        };
        return new HttpDecodedRequest(request, decoderFactory, ByteBufAllocator.DEFAULT, 0);
    }

    private static HttpData requestData(HttpRequest decoded, boolean withPooledObjects) {
        final CompletableFuture<HttpData> future = new CompletableFuture<>();
        final Subscriber<HttpObject> subscriber = new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpObject o) {
                if (o instanceof HttpData) {
                    future.complete((HttpData) o);
                }
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        };

        if (withPooledObjects) {
            decoded.subscribe(subscriber, SubscriptionOption.WITH_POOLED_OBJECTS);
        } else {
            decoded.subscribe(subscriber);
        }

        return future.join();
    }

    private static class CancelSubscriber implements Subscriber<HttpObject> {

        private Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpObject httpObject) {
            if (httpObject instanceof HttpData) {
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    }
}
