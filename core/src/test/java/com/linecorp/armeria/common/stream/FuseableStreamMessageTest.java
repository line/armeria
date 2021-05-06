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
import static org.awaitility.Awaitility.await;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.test.StepVerifier;

class FuseableStreamMessageTest {

    @Test
    void composedFilter() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5, 6, 7, 8);
        final StreamMessage<Integer> even = streamMessage.filter(x -> x % 2 == 0);
        final StreamMessage<Integer> biggerThan4 = even.filter(x -> x > 4);
        final FuseableStreamMessage<Integer, Integer> cast =
                (FuseableStreamMessage<Integer, Integer>) biggerThan4;

        // Should keep the original StreamMessage
        assertThat(cast.upstream()).isSameAs(streamMessage);

        StepVerifier.create(biggerThan4)
                    .thenRequest(1)
                    .expectNext(6)
                    .thenRequest(1)
                    .expectNext(8)
                    .verifyComplete();
    }

    @Test
    void composeFilterAndMap() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5, 6);
        final StreamMessage<Integer> even = streamMessage.filter(x -> x % 2 == 0);
        final StreamMessage<Integer> doubled = even.map(x -> x * 2);
        final FuseableStreamMessage<Integer, Integer> cast = (FuseableStreamMessage<Integer, Integer>) doubled;
        // Should keep the original StreamMessage
        assertThat(cast.upstream()).isSameAs(streamMessage);

        StepVerifier.create(doubled)
                    .expectNext(4, 8, 12)
                    .verifyComplete();
    }

    @Test
    void multipleMap() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5, 6);
        final StreamMessage<Boolean> result =
                streamMessage.map(x -> x - 1)  // Integer => Integer
                             .map(Objects::toString) // Integer => String
                             .map(str -> Integer.parseInt(str) % 2 == 0); // String => Boolean

        StepVerifier.create(result)
                    .expectNext(true, false, true, false, true, false)
                    .verifyComplete();
    }

    @Test
    void shouldReleaseHttpDataFilteredOut() {
        final ByteBuf[] bufs = new ByteBuf[7];
        final HttpData[] source = new HttpData[bufs.length];
        for (int i = 0; i < bufs.length; i++) {
            bufs[i] = Unpooled.copyInt(i);
            source[i] = HttpData.wrap(bufs[i]);
        }

        final StreamMessage<HttpData> filtered =
                StreamMessage.of(source)
                             .filter(data -> data.byteBuf().readInt() > 3);

        final AtomicBoolean completed = new AtomicBoolean();
        filtered.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                assertThat(httpData.byteBuf().refCnt()).isOne();
                httpData.close();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }, SubscriptionOption.WITH_POOLED_OBJECTS);

        await().untilTrue(completed);

        for (ByteBuf buf : bufs) {
            assertThat(buf.refCnt()).isZero();
        }
    }

    @Test
    void shouldReleaseBufWhenExceptionIsRaised() {
        final ByteBuf[] bufs = new ByteBuf[7];
        final HttpData[] source = new HttpData[bufs.length];
        for (int i = 0; i < bufs.length; i++) {
            bufs[i] = Unpooled.copyInt(i);
            source[i] = HttpData.wrap(bufs[i]);
        }
        final IllegalStateException cause = new IllegalStateException("Oops...");
        final StreamMessage<HttpData> filtered =
                StreamMessage.of(source)
                             .filter(data -> {
                                 if (data.byteBuf().readInt() < 3) {
                                     return true;
                                 } else {
                                     throw cause;
                                 }
                             });

        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        final AtomicInteger received = new AtomicInteger();
        filtered.subscribe(new Subscriber<HttpData>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(HttpData httpData) {
                received.incrementAndGet();
                assertThat(httpData.byteBuf().refCnt()).isOne();
                httpData.close();
            }

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {}
        }, SubscriptionOption.WITH_POOLED_OBJECTS);

        await().untilAsserted(() -> {
            assertThat(causeRef).hasValue(cause);
        });

        assertThat(received).hasValue(3);

        for (ByteBuf buf : bufs) {
            // Make sure that the unsubscribed elements is released.
            assertThat(buf.refCnt()).isZero();
        }
    }
}
