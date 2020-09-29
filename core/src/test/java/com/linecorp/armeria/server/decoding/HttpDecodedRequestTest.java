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

package com.linecorp.armeria.server.decoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public class HttpDecodedRequestTest {

    private static final Map<String, StreamDecoderFactory> DECODERS =
            ImmutableMap.of("gzip", StreamDecoderFactory.gzip());

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
        final HttpRequest decoded = new HttpDecodedRequest(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final HttpData decodedPayload = requestData(decoded, false);

        assertThat(decodedPayload.isPooled()).isFalse();
    }

    @Test
    void pooledPayload_unpooledDrain() {
        final ByteBuf payloadBuf = ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD);
        final HttpData payload = HttpData.wrap(payloadBuf).withEndOfStream();
        final HttpRequest delegate = HttpRequest.of(REQUEST_HEADERS, payload);
        final HttpRequest decoded = new HttpDecodedRequest(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final HttpData decodedPayload = requestData(decoded, false);

        assertThat(decodedPayload.isPooled()).isFalse();
        assertThat(payloadBuf.refCnt()).isZero();
    }

    @Test
    void unpooledPayload_pooledDrain() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpRequest delegate = HttpRequest.of(REQUEST_HEADERS, payload);
        final HttpRequest decoded = new HttpDecodedRequest(delegate, DECODERS, ByteBufAllocator.DEFAULT);
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
        final HttpRequest decoded = new HttpDecodedRequest(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final HttpData decodedPayload = requestData(decoded, true);
        final ByteBuf decodedPayloadBuf = decodedPayload.byteBuf();

        assertThat(payloadBuf.refCnt()).isZero();
        assertThat(decodedPayload.isPooled()).isTrue();
        decodedPayload.close();
        assertThat(decodedPayloadBuf.refCnt()).isZero();
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
}
