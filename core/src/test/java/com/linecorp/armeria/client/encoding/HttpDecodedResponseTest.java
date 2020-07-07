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

package com.linecorp.armeria.client.encoding;

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
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.unsafe.PooledHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;

class HttpDecodedResponseTest {

    private static final Map<String, StreamDecoderFactory> DECODERS =
            ImmutableMap.of("gzip", StreamDecoderFactory.gzip());

    private static final ResponseHeaders RESPONSE_HEADERS =
            ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_ENCODING, "gzip");

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
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final HttpResponse decoded = new HttpDecodedResponse(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final ByteBuf buf = responseBuf(decoded, false);

        assertThat(buf).isInstanceOf(UnpooledHeapByteBuf.class);
    }

    @Test
    void pooledPayload_unpooledDrain() {
        final PooledHttpData payload = PooledHttpData.wrap(
                ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD)).withEndOfStream();
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final HttpResponse decoded = new HttpDecodedResponse(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final ByteBuf buf = responseBuf(decoded, false);

        assertThat(buf).isInstanceOf(UnpooledHeapByteBuf.class);
        assertThat(payload.refCnt()).isZero();
    }

    // Users that request pooled objects still always need to be ok with unpooled ones.
    @Test
    void unpooledPayload_pooledDrain() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final HttpResponse decoded = new HttpDecodedResponse(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final ByteBuf buf = responseBuf(decoded, true);

        assertThat(buf).isNotInstanceOf(UnpooledHeapByteBuf.class);
    }

    @Test
    void pooledPayload_pooledDrain() {
        final PooledHttpData payload = PooledHttpData.wrap(
                ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD)).withEndOfStream();
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final HttpResponse decoded = new HttpDecodedResponse(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final ByteBuf buf = responseBuf(decoded, true);

        assertThat(buf).isNotInstanceOf(UnpooledHeapByteBuf.class);
        assertThat(payload.refCnt()).isZero();
    }

    private static ByteBuf responseBuf(HttpResponse decoded, boolean withPooledObjects) {
        final CompletableFuture<ByteBuf> future = new CompletableFuture<>();
        if (withPooledObjects) {
            decoded.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject o) {
                    if (o instanceof PooledHttpData) {
                        future.complete(((PooledHttpData) o).content());
                    }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onComplete() {}
            }, SubscriptionOption.WITH_POOLED_OBJECTS);
        } else {
            decoded.subscribe(new Subscriber<HttpObject>() {
                @Override
                public void onSubscribe(Subscription s) {
                    s.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(HttpObject o) {
                    if (o instanceof PooledHttpData) {
                        future.complete(((PooledHttpData) o).content());
                    }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onComplete() {}
            });
        }
        return future.join();
    }
}
