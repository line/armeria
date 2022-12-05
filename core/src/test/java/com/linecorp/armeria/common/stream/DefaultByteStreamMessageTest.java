/*
 * Copyright 2022 LINE Corporation
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.internal.testing.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.test.StepVerifier;

class DefaultByteStreamMessageTest {

    @Test
    void factory() {
        final StreamMessage<HttpData> delegate = StreamMessage.of(HttpData.ofUtf8("Hello"),
                                                                  HttpData.ofUtf8("Armeria"));
        final ByteStreamMessage byteStreamMessage = ByteStreamMessage.of(delegate);
        assertThat(byteStreamMessage).isInstanceOf(DefaultByteStreamMessage.class);
        assertThat(ByteStreamMessage.of(byteStreamMessage)).isSameAs(byteStreamMessage);
    }

    @Test
    void readAll() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage = ByteStreamMessage.of(delegate);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.ofUtf8("1"))
                    .expectNext(HttpData.ofUtf8("22"))
                    .expectNext(HttpData.ofUtf8("333"))
                    .expectNext(HttpData.ofUtf8("4444"))
                    .expectNext(HttpData.ofUtf8("55555"))
                    .verifyComplete();
    }

    @Test
    void zeroLength() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage = ByteStreamMessage.of(delegate);
        assertThatThrownBy(() -> byteStreamMessage.range(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length: 0 (expected: > 0)");
    }

    @Test
    void skip1() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage = ByteStreamMessage.of(delegate).range(1, Long.MAX_VALUE);
        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.ofUtf8("22"))
                    .thenRequest(2)
                    .expectNext(HttpData.ofUtf8("333"), HttpData.ofUtf8("4444"))
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("55555"))
                    .verifyComplete();
    }

    @Test
    void skip1_take1() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate).range(1, 1);
        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.ofUtf8("2"))
                    .verifyComplete();
    }

    @Test
    void skip1_take2() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate).range(1, 2);
        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.ofUtf8("22"))
                    .verifyComplete();
    }

    @Test
    void skip0_take2() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate)
                                 .range(0, 2);
        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.ofUtf8("1"))
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("2"))
                    .verifyComplete();
    }

    @Test
    void skip0_take4() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate)
                                 .range(0, 4);
        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.ofUtf8("1"))
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("22"))
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("3"))
                    .verifyComplete();
    }

    @Test
    void skip2_take4() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate).range(2, 4);
        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.ofUtf8("2"))
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("333"))
                    .verifyComplete();
    }

    @Test
    void skip2_take8() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate).range(2, 8);
        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.ofUtf8("2"))
                    .thenRequest(1)
                    .expectNext(HttpData.ofUtf8("333"))
                    .thenRequest(Long.MAX_VALUE)
                    .expectNext(HttpData.ofUtf8("4444"))
                    .verifyComplete();
    }

    @Test
    void skip3_take8() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate).range(3, 8);
        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.ofUtf8("333"))
                    .thenRequest(2)
                    .expectNext(HttpData.ofUtf8("4444"))
                    .expectNext(HttpData.ofUtf8("5"))
                    .verifyComplete();
    }

    @Test
    void skip4_take1() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate).range(4, 1);
        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(HttpData.ofUtf8("3"))
                    .verifyComplete();
    }

    @Test
    void skipAll() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate).range(15, Integer.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .verifyComplete();
    }

    @Test
    void skipMost() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final ByteStreamMessage byteStreamMessage =
                ByteStreamMessage.of(delegate).range(14, Integer.MAX_VALUE);

        StepVerifier.create(byteStreamMessage)
                    .expectNext(HttpData.ofUtf8("5"))
                    .verifyComplete();
    }

    @Test
    void collectBytes() {
        final StreamMessage<HttpData> delegate = newStreamMessage();
        final byte[] bytes = ByteStreamMessage.of(delegate).collectBytes().join();
        assertThat(bytes).isEqualTo("122333444455555".getBytes());
    }

    @Test
    void leakTest_delegate() {
        final List<ByteBuf> bufs = IntStream.range(1, 4).mapToObj(n -> {
            return Unpooled.wrappedBuffer(Strings.repeat(String.valueOf(n), n).getBytes());
        }).collect(toImmutableList());

        final StreamMessage<HttpData> delegate = StreamMessage.of(HttpData.wrap(bufs.get(0)),
                                                                  HttpData.wrap(bufs.get(1)),
                                                                  HttpData.wrap(bufs.get(2)));
        final List<HttpData> data =
                ByteStreamMessage.of(delegate).collect(SubscriptionOption.WITH_POOLED_OBJECTS).join();
        for (int i = 0; i < data.size(); i++) {
            final HttpData httpData = data.get(i);
            final ByteBuf byteBuf = bufs.get(i);
            assertThat(httpData.byteBuf()).isEqualTo(byteBuf);
            httpData.close();
            assertThat(byteBuf.refCnt()).isZero();
        }
    }

    @Test
    void leakTest_useHttpDataAsIs() {
        final List<ByteBuf> bufs = IntStream.range(1, 4).mapToObj(n -> {
            return Unpooled.wrappedBuffer(Strings.repeat(String.valueOf(n), n).getBytes());
        }).collect(toImmutableList());

        final StreamMessage<HttpData> delegate = StreamMessage.of(HttpData.wrap(bufs.get(0)),
                                                                  HttpData.wrap(bufs.get(1)),
                                                                  HttpData.wrap(bufs.get(2)));
        final List<HttpData> data =
                ByteStreamMessage.of(delegate)
                                 .collect(SubscriptionOption.WITH_POOLED_OBJECTS).join();
        for (int i = 0; i < data.size(); i++) {
            final HttpData httpData = data.get(i);
            final ByteBuf byteBuf = bufs.get(i);
            assertThat(httpData.byteBuf()).isEqualTo(byteBuf);
            httpData.close();
            assertThat(byteBuf.refCnt()).isZero();
        }
    }

    @Test
    void leakTest_drop() {
        final List<ByteBuf> bufs = IntStream.range(1, 4).mapToObj(n -> {
            return Unpooled.wrappedBuffer(Strings.repeat(String.valueOf(n), n).getBytes());
        }).collect(toImmutableList());

        final StreamMessage<HttpData> delegate = StreamMessage.of(HttpData.wrap(bufs.get(0)),
                                                                  HttpData.wrap(bufs.get(1)),
                                                                  HttpData.wrap(bufs.get(2)));
        final List<HttpData> data =
                ByteStreamMessage.of(delegate)
                                 .range(0, 4)
                                 .collect(SubscriptionOption.WITH_POOLED_OBJECTS).join();
        assertThat(data).hasSize(3);
        assertThat(data.stream().map(HttpData::toStringUtf8))
                .containsExactly("1", "22", "3");
        for (int i = 0; i < data.size(); i++) {
            final HttpData httpData = data.get(i);
            httpData.close();
        }
        for (final ByteBuf buf : bufs) {
            assertThat(buf.refCnt()).isZero();
        }
    }

    @Test
    void abortWhileHandlingBuffer() {
        final ByteBuf byteBuf1 = Unpooled.wrappedBuffer("A".getBytes());
        final ByteBuf byteBuf2 = Unpooled.wrappedBuffer("B".getBytes());
        final ByteBuf byteBuf3 = Unpooled.wrappedBuffer("C".getBytes());
        final StreamMessage<HttpData> delegate = StreamMessage.of(HttpData.wrap(byteBuf1),
                                                                  HttpData.wrap(byteBuf2),
                                                                  HttpData.wrap(byteBuf3));
        final ByteStreamMessage streamMessage = ByteStreamMessage.of(delegate);

        final AnticipatedException abortCause = new AnticipatedException("abort!");
        final AtomicReference<Throwable> abortCauseRef = new AtomicReference<>();
        streamMessage.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(HttpData data) {
                assertThat(data.isPooled()).isTrue();
                assertThat(data.toStringUtf8()).isEqualTo("A");
                data.close();
                streamMessage.abort(abortCause);
            }

            @Override
            public void onError(Throwable t) {
                abortCauseRef.set(t);
            }

            @Override
            public void onComplete() {}
        }, SubscriptionOption.WITH_POOLED_OBJECTS);

        await().untilAsserted(() -> {
            assertThat(abortCauseRef.get())
                    .isInstanceOf(AnticipatedException.class)
                    .hasMessageContaining("abort!");
        });
        assertThat(byteBuf1.refCnt()).isZero();
        assertThat(byteBuf2.refCnt()).isZero();
        assertThat(byteBuf3.refCnt()).isZero();
    }

    @Test
    void cancelWhileHandlingBuffer() {
        final ByteBuf byteBuf1 = Unpooled.wrappedBuffer("A".getBytes());
        final ByteBuf byteBuf2 = Unpooled.wrappedBuffer("B".getBytes());
        final ByteBuf byteBuf3 = Unpooled.wrappedBuffer("C".getBytes());
        final StreamMessage<HttpData> delegate = StreamMessage.of(HttpData.wrap(byteBuf1),
                                                                  HttpData.wrap(byteBuf2),
                                                                  HttpData.wrap(byteBuf3));
        final ByteStreamMessage streamMessage = ByteStreamMessage.of(delegate);

        final AtomicReference<Throwable> abortCauseRef = new AtomicReference<>();
        streamMessage.subscribe(new Subscriber<HttpData>() {

            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(HttpData data) {
                assertThat(data.isPooled()).isTrue();
                assertThat(data.toStringUtf8()).isEqualTo("A");
                data.close();
                subscription.cancel();
            }

            @Override
            public void onError(Throwable t) {
                abortCauseRef.set(t);
            }

            @Override
            public void onComplete() {}
        }, SubscriptionOption.WITH_POOLED_OBJECTS, SubscriptionOption.NOTIFY_CANCELLATION);

        await().untilAsserted(() -> {
            assertThat(abortCauseRef.get())
                    .isInstanceOf(CancelledSubscriptionException.class);
        });
        assertThat(byteBuf1.refCnt()).isZero();
        assertThat(byteBuf2.refCnt()).isZero();
        assertThat(byteBuf3.refCnt()).isZero();
    }

    @Test
    void shouldHandleBufferRemainingAfterOnComplete() throws InterruptedException {
        final ByteBuf byteBuf1 = Unpooled.wrappedBuffer("A".getBytes());
        final ByteBuf byteBuf2 = Unpooled.wrappedBuffer("B".getBytes());
        final ByteBuf byteBuf3 = Unpooled.wrappedBuffer("C".getBytes());
        final StreamMessage<HttpData> delegate = StreamMessage.of(HttpData.wrap(byteBuf1),
                                                                  HttpData.wrap(byteBuf2),
                                                                  HttpData.wrap(byteBuf3));
        final ByteStreamMessage streamMessage = ByteStreamMessage.of(delegate);

        final LinkedTransferQueue<HttpData> queue = new LinkedTransferQueue<>();
        EventLoopGroups.warmUp(CommonPools.workerGroup());
        streamMessage.subscribe(new Subscriber<HttpData>() {

            @Nullable
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(HttpData data) {
                queue.add(data);
                // Reschedule to avoid recursive calls.
                CommonPools.workerGroup().next().execute(() -> subscription.request(1));
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                queue.add(HttpData.ofUtf8("completed"));
            }
        }, EventLoopGroups.directEventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);

        streamMessage.whenComplete().join();
        HttpData httpData = queue.take();
        assertThat(httpData).isEqualTo(HttpData.ofUtf8("A"));
        httpData.close();

        httpData = queue.take();
        assertThat(httpData).isEqualTo(HttpData.ofUtf8("B"));
        httpData.close();

        httpData = queue.take();
        assertThat(httpData).isEqualTo(HttpData.ofUtf8("C"));
        httpData.close();

        httpData = queue.take();
        assertThat(httpData).isEqualTo(HttpData.ofUtf8("completed"));
        httpData.close();

        assertThat(byteBuf1.refCnt()).isZero();
        assertThat(byteBuf2.refCnt()).isZero();
        assertThat(byteBuf3.refCnt()).isZero();
    }

    private static StreamMessage<HttpData> newStreamMessage() {
        return StreamMessage.of(HttpData.ofUtf8("1"),
                                HttpData.ofUtf8("22"),
                                HttpData.ofUtf8("333"),
                                HttpData.ofUtf8("4444"),
                                HttpData.ofUtf8("55555"));
    }
}
