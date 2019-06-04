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

import static com.linecorp.armeria.common.stream.SubscriptionOption.WITH_POOLED_OBJECTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;

public class HttpDecodedResponseTest {

    private static final Map<String, StreamDecoderFactory> DECODERS =
            ImmutableMap.of("gzip", new GzipStreamDecoderFactory());

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
    public void unpooledPayload_unpooledDrain() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final HttpResponse decoded = new HttpDecodedResponse(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final ByteBuf buf = responseBuf(decoded, false);

        assertThat(buf).isInstanceOf(UnpooledHeapByteBuf.class);
    }

    @Test
    public void pooledPayload_unpooledDrain() {
        final ByteBufHttpData payload = new ByteBufHttpData(
                ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD), true);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final HttpResponse decoded = new HttpDecodedResponse(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final ByteBuf buf = responseBuf(decoded, false);

        assertThat(buf).isInstanceOf(UnpooledHeapByteBuf.class);
        assertThat(payload.refCnt()).isZero();
    }

    // Users that request pooled objects still always need to be ok with unpooled ones.
    @Test
    public void unpooledPayload_pooledDrain() {
        final HttpData payload = HttpData.wrap(PAYLOAD);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final HttpResponse decoded = new HttpDecodedResponse(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final ByteBuf buf = responseBuf(decoded, true);

        assertThat(buf).isNotInstanceOf(UnpooledHeapByteBuf.class);
    }

    @Test
    public void pooledPayload_pooledDrain() {
        final ByteBufHttpData payload = new ByteBufHttpData(
                ByteBufAllocator.DEFAULT.buffer().writeBytes(PAYLOAD), true);
        final HttpResponse delegate = HttpResponse.of(RESPONSE_HEADERS, payload);
        final HttpResponse decoded = new HttpDecodedResponse(delegate, DECODERS, ByteBufAllocator.DEFAULT);
        final ByteBuf buf = responseBuf(decoded, true);

        assertThat(buf).isNotInstanceOf(UnpooledHeapByteBuf.class);
        assertThat(payload.refCnt()).isZero();
    }

    private static ByteBuf responseBuf(HttpResponse decoded, boolean withPooledObjects) {
        final CompletableFuture<List<HttpObject>> future;
        if (withPooledObjects) {
            future = decoded.drainAll(WITH_POOLED_OBJECTS);
        } else {
            future = decoded.drainAll();
        }
        return future.join().stream()
                .filter(o -> o instanceof ByteBufHttpData)
                .map(o -> ((ByteBufHttpData) o).content())
                .findFirst()
                .get();
    }
}
