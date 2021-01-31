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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;

class ConcatPublisherStreamMessageTest {

    private ByteBuf[] bufs;

    private StreamMessage<HttpData> oneStreamMessage;
    private StreamMessage<HttpData> twoStreamMessage;
    private StreamMessage<HttpData> regularStreamMessage;
    private StreamMessage<HttpData> publisherBasedStreamMessage;
    private StreamMessage<HttpData> concatenated;

    @BeforeEach
    void setUp() {
        bufs = new ByteBuf[7];
        final HttpData[] httpData = new HttpData[bufs.length];
        for (int i = 0; i < bufs.length; i++) {
            bufs[i] = Unpooled.copyInt(i);
            httpData[i] = HttpData.wrap(bufs[i]);
        }

        oneStreamMessage = StreamMessage.of(httpData[0]);
        twoStreamMessage = StreamMessage.of(httpData[1], httpData[2]);
        regularStreamMessage = StreamMessage.of(httpData[3], httpData[4], httpData[5]);

        final Flux<HttpData> publisher = Flux.<HttpData>create(emitter -> emitter.next(httpData[6]))
                .doOnDiscard(HttpData.class, HttpData::close);
        publisherBasedStreamMessage = StreamMessage.of(publisher);

        concatenated = StreamMessage.concat(StreamMessage.of(oneStreamMessage, twoStreamMessage,
                                                             regularStreamMessage,
                                                             publisherBasedStreamMessage));
    }

    @AfterEach
    void tearDown() {
        for (ByteBuf buf : bufs) {
            await().untilAsserted(() -> {
                assertThat(buf.refCnt()).isZero();
            });
        }
    }

    @RepeatedTest(1000)
    void cancelStreamMessages() {
        final AtomicReference<HttpData> dataRef = new AtomicReference<>();
        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        concatenated.subscribe(new Subscriber<HttpData>() {

            @Override
            public void onSubscribe(Subscription s) {
                subscriptionRef.set(s);
                s.request(1);
            }

            @Override
            public void onNext(HttpData httpData) {
                assertThat(dataRef.get()).isNull();
                dataRef.set(httpData);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });

        await().untilAtomic(dataRef, Matchers.notNullValue());
        subscriptionRef.get().cancel();

        // A successfully closed stream
        oneStreamMessage.whenComplete().join();

        // Should cancel other stream messages
        assertCancellation(concatenated.whenComplete());
        assertCancellation(twoStreamMessage.whenComplete());
        assertCancellation(regularStreamMessage.whenComplete());
        assertCancellation(publisherBasedStreamMessage.whenComplete());
    }

    @Test
    void abortStreamMessages() {
        final AtomicReference<HttpData> dataRef = new AtomicReference<>();
        concatenated.subscribe(new Subscriber<HttpData>() {

            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(HttpData httpData) {
                assertThat(dataRef.get()).isNull();
                dataRef.set(httpData);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });

        await().untilAtomic(dataRef, Matchers.notNullValue());
        final IllegalStateException cause = new IllegalStateException("Oops...");
        concatenated.abort(cause);

        // A successfully closed stream
        oneStreamMessage.whenComplete().join();

        // Should abort other stream messages
        assertAbortion(concatenated.whenComplete(), cause);
        assertAbortion(twoStreamMessage.whenComplete(), cause);
        assertAbortion(regularStreamMessage.whenComplete(), cause);
        assertAbortion(publisherBasedStreamMessage.whenComplete(), cause);
    }

    @Test
    void oneFixedStreamMessageShouldAbortElements() {
        final StreamMessage<Integer> inner = StreamMessage.of(1);
        final StreamMessage<StreamMessage<Integer>> one = new OneElementFixedStreamMessage<>(inner);

        final StreamMessage<Integer> concat = StreamMessage.concat(one);
        final IllegalStateException cause = new IllegalStateException("Oops...");
        concat.abort(cause);

        assertAbortion(one.whenComplete(), cause);
        assertAbortion(inner.whenComplete(), cause);
        assertAbortion(concat.whenComplete(), cause);

        cleanup();
    }

    @Test
    void twoFixedStreamMessageShouldAbortElements() {
        final StreamMessage<Integer> inner1 = StreamMessage.of(1);
        final StreamMessage<Integer> inner2 = StreamMessage.of(2);
        final StreamMessage<StreamMessage<Integer>> two = StreamMessage.of(inner1, inner2);

        final StreamMessage<Integer> concat = StreamMessage.concat(two);
        final IllegalStateException cause = new IllegalStateException("Oops...");
        concat.abort(cause);

        assertAbortion(two.whenComplete(), cause);
        assertAbortion(inner1.whenComplete(), cause);
        assertAbortion(inner2.whenComplete(), cause);
        assertAbortion(concat.whenComplete(), cause);

        cleanup();
    }

    @Test
    void regularFixedStreamMessageShouldAbortElements() {
        final StreamMessage<Integer> inner1 = StreamMessage.of(1);
        final StreamMessage<Integer> inner2 = StreamMessage.of(2);
        final StreamMessage<Integer> inner3 = StreamMessage.of(3);
        final StreamMessage<StreamMessage<Integer>> regular = StreamMessage.of(inner1, inner2, inner3);

        final StreamMessage<Integer> concat = StreamMessage.concat(regular);
        final IllegalStateException cause = new IllegalStateException("Oops...");
        concat.abort(cause);

        assertAbortion(regular.whenComplete(), cause);
        assertAbortion(inner1.whenComplete(), cause);
        assertAbortion(inner2.whenComplete(), cause);
        assertAbortion(inner3.whenComplete(), cause);
        assertAbortion(concat.whenComplete(), cause);

        cleanup();
    }

    @Test
    void defaultStreamMessageShouldAbortElements() {
        final StreamMessage<Integer> inner = StreamMessage.of(1);
        final DefaultStreamMessage<StreamMessage<Integer>> defaultStreamMessage = new DefaultStreamMessage<>();
        defaultStreamMessage.write(inner);
        defaultStreamMessage.close();

        final StreamMessage<Integer> concat = StreamMessage.concat(defaultStreamMessage);
        final IllegalStateException cause = new IllegalStateException("Oops...");
        concat.abort(cause);

        assertAbortion(defaultStreamMessage.whenComplete(), cause);
        assertAbortion(inner.whenComplete(), cause);
        assertAbortion(concat.whenComplete(), cause);

        cleanup();
    }

    private void cleanup() {
        for (ByteBuf buf : bufs) {
            buf.release();
        }
    }

    private static void assertCancellation(CompletableFuture<?> completeFuture) {
        assertThatThrownBy(completeFuture::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CancelledSubscriptionException.class);
    }

    private static void assertAbortion(CompletableFuture<?> completeFuture, Throwable cause) {
        assertThatThrownBy(completeFuture::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);
    }
}
