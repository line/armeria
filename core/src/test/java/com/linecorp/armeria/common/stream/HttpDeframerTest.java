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

package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class HttpDeframerTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void mapNToZero() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final HttpDeframer<String> deframer = HttpDeframer.of(decoder, ByteBufAllocator.DEFAULT);
        final Flux<HttpData> stream = Flux.just(HttpData.ofUtf8("A012345"), HttpData.ofUtf8("67"));
        stream.subscribe(deframer);
        StepVerifier.create(deframer)
                    .expectComplete()
                    .verify();
        assertThat(decoder.isReleased()).isTrue();
    }

    @Test
    void mapTwoToOne() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final Flux<HttpData> stream = Flux.just("A012345", "6789B1234")
                                          .map(HttpData::ofUtf8);
        final HttpDeframer<String> deframer = HttpDeframer.of(decoder, ByteBufAllocator.DEFAULT);
        stream.subscribe(deframer);
        StepVerifier.create(deframer)
                    .expectNext("A0123456789")
                    .expectComplete()
                    .verify();
        assertThat(decoder.isReleased()).isTrue();
    }

    @Test
    void mapNToN() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final HttpDeframer<String> deframer = HttpDeframer.of(decoder, ByteBufAllocator.DEFAULT);
        final Flux<HttpData> stream = Flux.just("A0123456789",
                                                "B0123456789",
                                                "C0123456789",
                                                "D0123456789",
                                                "E0123456789")
                                          .map(HttpData::ofUtf8);
        stream.subscribe(deframer);
        StepVerifier.create(deframer)
                    .expectNext("A0123456789")
                    .expectNext("B0123456789")
                    .expectNext("C0123456789")
                    .expectNext("D0123456789")
                    .expectNext("E0123456789")
                    .expectComplete()
                    .verify();
        assertThat(decoder.isReleased()).isTrue();
    }

    @Test
    void mapMToN() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final HttpDeframer<String> deframer = HttpDeframer.of(decoder, ByteBufAllocator.DEFAULT);
        final Flux<HttpData> stream = Flux.just("A012345",
                                                "6789B0",
                                                "12",
                                                "",
                                                "3",
                                                "456789",
                                                "C01234",
                                                "56789D",
                                                "0123456789E0123456789")
                                          .map(HttpData::ofUtf8);
        stream.subscribe(deframer);
        StepVerifier.create(deframer)
                    .expectNext("A0123456789")
                    .expectNext("B0123456789")
                    .expectNext("C0123456789")
                    .expectNext("D0123456789")
                    .expectNext("E0123456789")
                    .expectComplete()
                    .verify();
        assertThat(decoder.isReleased()).isTrue();
    }

    @Test
    void mapNToOne() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final HttpDeframer<String> deframer = HttpDeframer.of(decoder, ByteBufAllocator.DEFAULT);
        final Flux<HttpData> stream = Flux.just(HttpData.empty(), HttpData.ofUtf8("A0123456"),
                                                HttpData.empty(), HttpData.ofUtf8("789B"));
        stream.subscribe(deframer);
        StepVerifier.create(deframer)
                    .expectNext("A0123456789")
                    .expectComplete()
                    .verify();
        assertThat(decoder.isReleased()).isTrue();
    }

    @Test
    void consumeExpectedCount() throws InterruptedException {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final HttpDeframer<String> deframer = HttpDeframer.of(decoder, ByteBufAllocator.DEFAULT);
        final Flux<HttpData> stream = Flux.just("A012345",
                                                "6789B0",
                                                "12",
                                                "",
                                                "3",
                                                "456789",
                                                "C01234",
                                                "56789D",
                                                "0123456789E0123456789")
                                          .map(HttpData::ofUtf8);

        stream.subscribe(deframer);
        final List<String> consumed = new ArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        deframer.subscribe(new Subscriber<String>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscriptionRef.set(s);
                s.request(2);
            }

            @Override
            public void onNext(String s) {
                consumed.add(s);
            }

            @Override
            public void onError(Throwable t) {
                completed.set(true);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });
        // Give enough time to subscribe
        Thread.sleep(1000);

        assertThat(completed).isFalse();
        assertThat(consumed).containsExactly("A0123456789", "B0123456789");
        subscriptionRef.get().request(1);

        // Give enough time to subscribe
        Thread.sleep(1000);

        assertThat(completed).isFalse();
        assertThat(consumed).containsExactly("A0123456789", "B0123456789", "C0123456789");
        subscriptionRef.get().cancel();
        assertThat(decoder.isReleased()).isTrue();
    }

    @Test
    void headerAwareData() {
        final Flux<HttpHeaders> headers = Flux.just(ResponseHeaders.of(HttpStatus.CONTINUE),
                                                    HttpHeaders.of("length", 11));
        final Flux<HttpData> stream = Flux.just("A012345",
                                                "6789B0",
                                                "12",
                                                "",
                                                "3",
                                                "456789",
                                                "C01234",
                                                "56789D",
                                                "0123456789E0123456789")
                                          .map(HttpData::ofUtf8);

        final HeaderAwareDecoder decoder = new HeaderAwareDecoder();
        final HttpDeframer<String> deframer = HttpDeframer.of(decoder, ByteBufAllocator.DEFAULT);

        Flux.concat(headers, stream).subscribe(deframer);
        StepVerifier.create(deframer)
                    .expectNext("A0123456789")
                    .expectNext("B0123456789")
                    .expectNext("C0123456789")
                    .expectNext("D0123456789")
                    .expectNext("E0123456789")
                    .expectComplete()
                    .verify();
        assertThat(decoder.isReleased()).isTrue();
    }

    @Test
    void deferredError() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final HttpDeframer<String> deframer = HttpDeframer.of(decoder, ByteBufAllocator.DEFAULT);
        final RuntimeException cause = new RuntimeException("Error before subscribing");
        final Flux<HttpData> stream = Flux.error(cause);
        stream.subscribe(deframer);
        final EventLoop eventLoop = HttpDeframerTest.eventLoop.get();
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        deframer.subscribe(new Subscriber<String>() {
            @Override
            public void onSubscribe(Subscription s) {}

            @Override
            public void onNext(String o) {}

            @Override
            public void onError(Throwable t) {
                assertThat(eventLoop.inEventLoop()).isTrue();
                causeRef.set(t);
            }

            @Override
            public void onComplete() {}
        }, eventLoop);
        await().untilAtomic(causeRef, Matchers.is(cause));
    }

    private static final class FixedLengthDecoder implements HttpDeframerHandler<String> {

        private final int length;
        private final List<ByteBuf> byteBufs = new ArrayList<>();

        private FixedLengthDecoder(int length) {
            this.length = length;
        }

        @Override
        public void process(HttpDeframerInput in, HttpDeframerOutput<String> out) {
            int remaining = in.readableBytes();
            if (remaining < length) {
                return;
            }

            while (remaining >= length) {
                final ByteBuf buf = in.readBytes(length);
                out.add(buf.toString(StandardCharsets.UTF_8));
                byteBufs.add(buf);
                buf.release();
                remaining -= length;
            }
        }

        boolean isReleased() {
            return byteBufs.stream().allMatch(buf -> buf.refCnt() == 0);
        }
    }

    private static final class HeaderAwareDecoder implements HttpDeframerHandler<String> {

        private int length;
        private final List<ByteBuf> byteBufs = new ArrayList<>();

        @Override
        public void processHeaders(HttpHeaders in, HttpDeframerOutput<String> out) {
            length = in.getInt("length", -1);
            assertThat(length).isGreaterThan(0);
        }

        @Override
        public void process(HttpDeframerInput in, HttpDeframerOutput<String> out) {
            int remaining = in.readableBytes();
            if (remaining < length) {
                return;
            }

            while (remaining >= length) {
                final ByteBuf buf = in.readBytes(length);
                out.add(buf.toString(StandardCharsets.UTF_8));
                byteBufs.add(buf);
                buf.release();
                remaining -= length;
            }
        }

        boolean isReleased() {
            return byteBufs.stream().allMatch(buf -> buf.refCnt() == 0);
        }
    }
}
