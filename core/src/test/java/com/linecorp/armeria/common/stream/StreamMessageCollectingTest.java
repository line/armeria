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

package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class StreamMessageCollectingTest {

    private static final HttpData[] HTTP_DATA = {};

    @Test
    void emptyStreamMessage() {
        final StreamMessage<Object> stream1 = StreamMessage.of();
        assertThat(stream1.collect().join()).isEqualTo(ImmutableList.of());

        final StreamMessage<Object> stream2 = StreamMessage.of();
        final Throwable cause = new IllegalStateException("oops");
        stream2.abort(cause);
        assertThatThrownBy(() -> stream2.collect().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);

        final DefaultStreamMessage<Object> stream3 = new DefaultStreamMessage<>();
        stream3.close();
        assertThat(stream3.collect().join()).isEqualTo(ImmutableList.of());

        final DefaultStreamMessage<Object> stream4 = new DefaultStreamMessage<>();
        final CompletableFuture<List<Object>> collectingFuture = stream4.collect();
        stream4.close();
        assertThat(collectingFuture.join()).isEqualTo(ImmutableList.of());

        final DefaultStreamMessage<Object> stream5 = new DefaultStreamMessage<>();
        stream5.abort(cause);
        assertThatThrownBy(() -> stream5.collect().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);
    }

    @CsvSource({ "1, true", "1, false",
                 "2, true", "2, false",
                 "3, true", "3, false",
                 "4, true", "4, false",
                 "100, true", "100, false" })
    @ParameterizedTest
    void closeOrAbortAndCollect(int size, boolean fixedStream) {
        Map<HttpData, ByteBuf> data = newHttpData(size);
        HttpData[] httpData = data.keySet().toArray(HTTP_DATA);
        final StreamMessage<HttpData> stream1 = newStreamMessage(httpData, fixedStream);
        assertData(stream1.collect().join(), size);
        assertRefCount(data, 0);

        data = newHttpData(size);
        httpData = data.keySet().toArray(HTTP_DATA);
        final StreamMessage<HttpData> stream2 = newStreamMessage(httpData, fixedStream);
        assertData(stream2.collect(SubscriptionOption.WITH_POOLED_OBJECTS).join(), size);
        assertRefCount(data, 1);
        releaseAll(data);

        data = newHttpData(size);
        httpData = data.keySet().toArray(HTTP_DATA);
        final StreamMessage<HttpData> stream3 = newStreamMessage(httpData, fixedStream);
        final Throwable cause = new IllegalStateException("oops");
        stream3.abort(cause);
        assertThatThrownBy(() -> stream3.collect().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);
        assertRefCount(data, 0);
    }

    @Test
    void collectAndClose() {
        final int size = 5;
        final Map<HttpData, ByteBuf> data = newHttpData(size);
        final DefaultStreamMessage<HttpData> stream = new DefaultStreamMessage<>();
        data.forEach((httpData, buf) -> stream.write(httpData));
        final CompletableFuture<List<HttpData>> collectingFuture = stream.collect();
        assertThat(collectingFuture).isNotDone();
        stream.close();
        assertData(collectingFuture.join(), size);
        assertRefCount(data, 0);
    }

    @Test
    void collectAndAbort() {
        final int size = 5;
        final Map<HttpData, ByteBuf> data = newHttpData(size);
        final DefaultStreamMessage<HttpData> stream = new DefaultStreamMessage<>();
        data.forEach((httpData, buf) -> stream.write(httpData));
        final CompletableFuture<List<HttpData>> collectingFuture = stream.collect();
        assertThat(collectingFuture).isNotDone();
        final Throwable cause = new IllegalStateException("oops");
        stream.abort(cause);
        assertThatThrownBy(collectingFuture::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);
        await().untilAsserted(() -> assertRefCount(data, 0));
    }

    @Test
    void filteredStreamMessage_exception() {
        final int size = 5;
        final Map<HttpData, ByteBuf> data = newHttpData(size);
        final HttpData[] httpData = data.keySet().toArray(HTTP_DATA);
        final StreamMessage<HttpData> stream = newStreamMessage(httpData, true);

        final Throwable cause = new IllegalStateException("oops");
        final StreamMessage<HttpData> filtered = new FilteredStreamMessage<HttpData, HttpData>(stream) {
            int count;

            @Override
            protected HttpData filter(HttpData obj) {
                count++;
                if (count < 2) {
                    return obj;
                } else {
                    return Exceptions.throwUnsafely(cause);
                }
            }
        };

        assertThatThrownBy(() -> {
            filtered.collect(SubscriptionOption.WITH_POOLED_OBJECTS).join();
        }).isInstanceOf(CompletionException.class)
          .hasCause(cause);

        assertRefCount(data, 0);
    }

    @Test
    void filteredStreamMessage_cancel() {
        final int size = 5;
        final Map<HttpData, ByteBuf> data = newHttpData(size);
        final HttpData[] httpData = data.keySet().toArray(HTTP_DATA);
        final StreamMessage<HttpData> stream = newStreamMessage(httpData, true);

        final StreamMessage<HttpData> filtered = new FilteredStreamMessage<HttpData, HttpData>(stream) {

            private Subscription subscription;
            int count;

            @Override
            protected void beforeSubscribe(Subscriber<? super HttpData> subscriber, Subscription subscription) {
                this.subscription = subscription;
            }

            @Override
            protected HttpData filter(HttpData obj) {
                count++;
                if (count < 2) {
                    return obj;
                } else {
                    subscription.cancel();
                    return obj;
                }
            }
        };

        final List<HttpData> collected = filtered.collect(SubscriptionOption.WITH_POOLED_OBJECTS).join();
        assertThat(collected).hasSize(2);

        final List<ByteBuf> bufs = ImmutableList.copyOf(data.values());

        assertThat(bufs.get(0).refCnt()).isOne();
        assertThat(bufs.get(1).refCnt()).isOne();
        assertThat(bufs.get(2).refCnt()).isZero();
        assertThat(bufs.get(3).refCnt()).isZero();
        assertThat(bufs.get(4).refCnt()).isZero();

        bufs.get(0).release();
        bufs.get(1).release();
    }

    @Test
    void fuseableStreamMessage_map() {
        final int size = 5;
        Map<HttpData, ByteBuf> data = newHttpData(size);
        HttpData[] httpData = data.keySet().toArray(HTTP_DATA);
        StreamMessage<HttpData> stream = newStreamMessage(httpData, false);
        List<HttpData> collected = stream.map(Function.identity())
                                         .collect(SubscriptionOption.WITH_POOLED_OBJECTS).join();

        assertData(collected, size);
        assertRefCount(data, 1);
        releaseAll(data);

        data = newHttpData(size);
        httpData = data.keySet().toArray(HTTP_DATA);
        stream = newStreamMessage(httpData, false);
        collected = stream.map(Function.identity()).collect().join();
        assertData(collected, size);
        assertRefCount(data, 0);

        data = newHttpData(size);
        httpData = data.keySet().toArray(HTTP_DATA);
        final StreamMessage<HttpData> stream1 = newStreamMessage(httpData, false);
        final AtomicInteger counter = new AtomicInteger();
        final Throwable cause = new IllegalStateException("oops");
        assertThatThrownBy(() -> {
            stream1.map(obj -> {
                if (counter.incrementAndGet() > 2) {
                    return Exceptions.throwUnsafely(cause);
                } else {
                    return obj;
                }
            }).collect(SubscriptionOption.WITH_POOLED_OBJECTS).join();
        }).isInstanceOf(CompletionException.class)
          .hasCause(cause);

        assertRefCount(data, 0);
    }

    @Test
    void fuseableStreamMessage_filter() {
        final int size = 5;
        Map<HttpData, ByteBuf> data = newHttpData(size);
        HttpData[] httpData = data.keySet().toArray(HTTP_DATA);
        final StreamMessage<HttpData> stream = newStreamMessage(httpData, false);
        final AtomicInteger counter = new AtomicInteger();

        final List<HttpData> collected = stream.filter(x -> counter.getAndIncrement() < 2)
                                               .collect(SubscriptionOption.WITH_POOLED_OBJECTS).join();
        assertThat(collected).hasSize(2);

        final List<ByteBuf> bufs = ImmutableList.copyOf(data.values());

        assertThat(bufs.get(0).refCnt()).isOne();
        assertThat(bufs.get(1).refCnt()).isOne();
        assertThat(bufs.get(2).refCnt()).isZero();
        assertThat(bufs.get(3).refCnt()).isZero();
        assertThat(bufs.get(4).refCnt()).isZero();

        bufs.get(0).release();
        bufs.get(1).release();

        data = newHttpData(size);
        httpData = data.keySet().toArray(HTTP_DATA);
        final StreamMessage<HttpData> stream1 = newStreamMessage(httpData, false);
        counter.set(0);

        final Throwable cause = new IllegalStateException("oops");
        assertThatThrownBy(() -> {
            stream1.filter(x -> {
                if (counter.getAndIncrement() < 2) {
                    return true;
                } else {
                    return Exceptions.throwUnsafely(cause);
                }
            }).collect(SubscriptionOption.WITH_POOLED_OBJECTS).join();
        }).isInstanceOf(CompletionException.class)
          .hasCause(cause);

        assertRefCount(data, 0);
    }

    private static StreamMessage<HttpData> newStreamMessage(HttpData[] httpData, boolean fixedStream) {
        if (fixedStream) {
            return StreamMessage.of(httpData);
        } else {
            final DefaultStreamMessage<HttpData> stream = new DefaultStreamMessage<>();
            for (HttpData data : httpData) {
                stream.write(data);
            }
            stream.close();
            return stream;
        }
    }

    private static Map<HttpData, ByteBuf> newHttpData(int size) {
        final ImmutableMap.Builder<HttpData, ByteBuf> builder = ImmutableMap.builderWithExpectedSize(size);
        for (int i = 0; i < size; i++) {
            final ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{ (byte) i });
            builder.put(HttpData.wrap(buf), buf);
        }
        return builder.build();
    }

    private static void assertData(List<HttpData> httpData, int size) {
        assertThat(httpData).hasSize(size);
        for (int i = 0; i < size; i++) {
            assertThat(httpData.get(i).array()).isEqualTo(new byte[]{ (byte) i });
        }
    }

    private static void assertRefCount(Map<HttpData, ByteBuf> data, int expected) {
        for (ByteBuf buf : data.values()) {
            assertThat(buf.refCnt()).isEqualTo(expected);
        }
    }

    private static void releaseAll(Map<HttpData, ByteBuf> data) {
        for (ByteBuf buf : data.values()) {
            buf.release();
        }
    }
}
