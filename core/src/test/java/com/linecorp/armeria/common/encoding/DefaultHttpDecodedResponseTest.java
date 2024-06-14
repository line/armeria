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

package com.linecorp.armeria.common.encoding;

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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.CompositeException;
import com.linecorp.armeria.internal.common.encoding.DefaultHttpDecodedResponse;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.compression.DecompressionException;
import io.netty.handler.codec.compression.SnappyFrameDecoder;
import reactor.test.StepVerifier;

class DefaultHttpDecodedResponseTest {

    private static final Map<String, StreamDecoderFactory> OLD_DECODER =
            ImmutableMap.of("gzip", StreamDecoderFactory.gzip());

    private static final Map<String, StreamDecoderFactory> DECODER =
            ImmutableMap.of("gzip", com.linecorp.armeria.common.encoding.StreamDecoderFactory.gzip());

    private static final ResponseHeaders RESPONSE_HEADERS =
            ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_ENCODING, "gzip");

    private static final String ORIGINAL_MESSAGE = "hello";
    private static final byte[] PAYLOAD;

    static {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(ORIGINAL_MESSAGE.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        PAYLOAD = bos.toByteArray();
    }

    @Test
    void unpooledPayload_unpooledDrain() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpResponse decoded =
                new DefaultHttpDecodedResponse(delegate, DECODER, ctx, false);
        final HttpData decodedPayload = responseData(decoded, false);

        assertThat(decodedPayload.isPooled()).isFalse();
    }

    @Test
    void pooledPayload_unpooledDrain() {
        final ByteBuf payloadBuf = ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD);
        final HttpData payload = HttpData.wrap(payloadBuf).withEndOfStream();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final HttpResponse decoded =
                new DefaultHttpDecodedResponse(delegate, DECODER, ctx, false);
        final HttpData decodedPayload = responseData(decoded, false);

        assertThat(decodedPayload.isPooled()).isFalse();
        assertThat(payloadBuf.refCnt()).isZero();
    }

    @Test
    void unpooledPayload_pooledDrain() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpResponse decoded = new DefaultHttpDecodedResponse(delegate, DECODER, ctx, false);
        final HttpData decodedPayload = responseData(decoded, true);

        assertThat(decodedPayload.isPooled()).isTrue();
        assertThat(decodedPayload.byteBuf().refCnt()).isOne();
        decodedPayload.close();
    }

    @Test
    void pooledPayload_pooledDrain() {
        final ByteBuf payloadBuf = ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD);
        final HttpData payload = HttpData.wrap(payloadBuf).withEndOfStream();
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpResponse decoded =
                new DefaultHttpDecodedResponse(delegate, DECODER, ctx, false);
        final HttpData decodedPayload = responseData(decoded, true);
        final ByteBuf decodedPayloadBuf = decodedPayload.byteBuf();

        assertThat(payloadBuf.refCnt()).isZero();
        assertThat(decodedPayload.isPooled()).isTrue();
        decodedPayload.close();
        assertThat(decodedPayloadBuf.refCnt()).isZero();
    }

    @Test
    void unpooledPayload_unpooledDrain_withOldDecoder() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpResponse decoded =
                new DefaultHttpDecodedResponse(delegate, OLD_DECODER, ctx, false);
        final HttpData decodedPayload = responseData(decoded, false);

        assertThat(decodedPayload.isPooled()).isFalse();
    }

    @Test
    void pooledPayload_unpooledDrain_withOldDecoder() {
        final ByteBuf payloadBuf = ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD);
        final HttpData payload = HttpData.wrap(payloadBuf).withEndOfStream();
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpResponse decoded =
                new DefaultHttpDecodedResponse(delegate, OLD_DECODER, ctx, false);
        final HttpData decodedPayload = responseData(decoded, false);

        assertThat(decodedPayload.isPooled()).isFalse();
        assertThat(payloadBuf.refCnt()).isZero();
    }

    @Test
    void unpooledPayload_pooledDrain_withOldDecoder() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpResponse decoded =
                new DefaultHttpDecodedResponse(delegate, OLD_DECODER, ctx, false);
        final HttpData decodedPayload = responseData(decoded, true);

        assertThat(decodedPayload.isPooled()).isTrue();
        assertThat(decodedPayload.byteBuf().refCnt()).isOne();
        decodedPayload.close();
    }

    @Test
    void pooledPayload_pooledDrain_withOldDecoder() {
        final ByteBuf payloadBuf = ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD);
        final HttpData payload = HttpData.wrap(payloadBuf).withEndOfStream();
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpResponse decoded =
                new DefaultHttpDecodedResponse(delegate, OLD_DECODER, ctx, false);
        final HttpData decodedPayload = responseData(decoded, true);
        final ByteBuf decodedPayloadBuf = decodedPayload.byteBuf();

        assertThat(payloadBuf.refCnt()).isZero();
        assertThat(decodedPayload.isPooled()).isTrue();
        decodedPayload.close();
        assertThat(decodedPayloadBuf.refCnt()).isZero();
    }

    @Test
    void streamDecoderFinishedIsCalledWhenRequestCanceled() {
        final HttpResponseWriter response = HttpResponse.streaming();
        response.write(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_ENCODING, "foo"));
        final HttpData data = HttpData.ofUtf8("bar");
        response.write(data);
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        final com.linecorp.armeria.common.encoding.StreamDecoderFactory factory = mock(
                com.linecorp.armeria.common.encoding.StreamDecoderFactory.class);
        final com.linecorp.armeria.common.encoding.StreamDecoder streamDecoder = mock(StreamDecoder.class);
        when(factory.newDecoder(any(), eq((int) ctx.maxResponseLength()))).thenReturn(streamDecoder);
        when(streamDecoder.decode(any())).thenReturn(data);

        final HttpResponse decoded = new DefaultHttpDecodedResponse(response, ImmutableMap.of("foo", factory),
                                                                    ctx, true);
        decoded.subscribe(new CancelSubscriber());

        await().untilAsserted(() -> verify(streamDecoder, times(1)).finish());
    }

    @Test
    void lengthLimit() {
        final ByteBuf payloadBuf = ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, HttpData.wrap(payloadBuf));
        final RequestOptions requestOptions = RequestOptions.builder()
                                                            .maxResponseLength(ORIGINAL_MESSAGE.length() - 1)
                                                            .build();
        final ClientRequestContext ctx =
                ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                    .requestOptions(requestOptions)
                                    .build();
        final HttpResponse decoded = new DefaultHttpDecodedResponse(delegate, DECODER, ctx, false);

        assertThatThrownBy(decoded.aggregate(AggregationOptions.usePooledObjects(ctx.alloc()))::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ContentTooLargeException.class)
                .hasRootCauseInstanceOf(DecompressionException.class)
                .satisfies(cause -> {
                    assertThat(((ContentTooLargeException) cause.getCause()).maxContentLength())
                            .isEqualTo(ctx.maxResponseLength());
                });
        assertThat(payloadBuf.refCnt()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldHandleExceptionInDecoderFinishOnComplete(boolean aggregation) {
        final HttpResponse decoded = newFailingDecodedResponse();
        if (aggregation) {
            assertThatThrownBy(() -> decoded.aggregate().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ContentTooLargeException.class);
        } else {
            StepVerifier.create(decoded)
                        // Content-Encoding header is removed by decoder.
                        .expectNext(ResponseHeaders.of(200))
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
        final HttpResponse decoded = newFailingDecodedResponse();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        decoded.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(HttpObject httpObject) {
                decoded.abort();
            }

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

    @Test
    void shouldExposeReasonWhenEncounterUnexpectedDecodeException() {
        final HttpData httpData = HttpData.of(StandardCharsets.UTF_8, "Hello");
        final StreamDecoder decoder = new AbstractStreamDecoder(new SnappyFrameDecoder(),
                                                                ByteBufAllocator.DEFAULT, 100);
        assertThatThrownBy(() -> decoder.decode(httpData))
                .isInstanceOf(DecompressionException.class);
    }

    private static HttpResponse newFailingDecodedResponse() {
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, HttpData.ofUtf8("Hello"));
        final ClientRequestContext ctx =
                ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

        final com.linecorp.armeria.common.encoding.StreamDecoderFactory decoderFactory =
                new com.linecorp.armeria.common.encoding.StreamDecoderFactory() {

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
        return new DefaultHttpDecodedResponse(delegate, ImmutableMap.of("gzip", decoderFactory), ctx, false);
    }

    private static HttpData responseData(HttpResponse decoded, boolean withPooledObjects) {
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
