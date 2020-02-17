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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

class HttpRequestTest {

    @Test
    void createWithVarArgs() {
        final RequestHeaders requestHeaders = RequestHeaders.of(HttpMethod.GET, "/foo");
        final HttpRequest request = HttpRequest.of(requestHeaders, HttpData.ofUtf8("a"), HttpData.ofUtf8("b"),
                                                   HttpData.ofUtf8("c"));

        final List<HttpObject> objects = request.drainAll().join();
        assertThat(objects).containsExactly(HttpData.ofUtf8("a"), HttpData.ofUtf8("b"), HttpData.ofUtf8("c"));
    }

    @Test
    void abortWithCause() {
        final HttpRequest request = HttpRequest.of(HttpMethod.GET, "/foo");
        final AtomicReference<Throwable> abortCauseHolder = new AtomicReference<>();
        request.subscribe(new Subscriber<HttpObject>() {
            @Override
            public void onSubscribe(Subscription subscription) {}

            @Override
            public void onNext(HttpObject httpObject) {}

            @Override
            public void onError(Throwable throwable) {
                abortCauseHolder.set(throwable);
            }

            @Override
            public void onComplete() {}
        });
        final IllegalStateException abortCause = new IllegalStateException("abort stream");
        request.abort(abortCause);
        await().untilAsserted(() -> {
            assertThat(abortCauseHolder).hasValue(abortCause);
        });
    }

    @Test
    void shouldReleaseEmptyContent() {
        final ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        assertThat(buf.readableBytes()).isZero();
        assertThat(buf.refCnt()).isOne();

        buf.retain();
        HttpRequest.of(HttpMethod.GET, "/", MediaType.PLAIN_TEXT_UTF_8, new ByteBufHttpData(buf, false));
        assertThat(buf.refCnt()).isOne();

        buf.retain();
        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"), new ByteBufHttpData(buf, false));
        assertThat(buf.refCnt()).isOne();

        buf.retain();
        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/"),
                       new ByteBufHttpData(buf, false),
                       HttpHeaders.of("some-trailer", "value"));
        assertThat(buf.refCnt()).isOne();

        buf.release();
    }
}
