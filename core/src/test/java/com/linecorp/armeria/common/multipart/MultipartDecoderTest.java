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
/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.multipart.MultipartEncoderTest.HttpDataAggregator;

/**
 * Tests {@link MultiPartDecoder}.
 */
public class MultipartDecoderTest {

    // Forked from https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/media/multipart/src/test/java/io/helidon/media/multipart/MultiPartDecoderTest.java

    @Test
    void testOnePartInOneChunk() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "body 1\n" +
                               "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(2);
        final Consumer<BodyPart> consumer = part -> {
            counter.getAndDecrement();
            assertThat(part.headers().get("Content-Id")).contains("part1");
            final HttpDataAggregator subscriber = new HttpDataAggregator();
            part.content().subscribe(subscriber);
            subscriber.content().thenAccept(body -> {
                counter.getAndDecrement();
                assertThat(body).isEqualTo("body 1");
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.error).isNull();
        assertThat(testSubscriber.complete).isTrue();
    }

    @Test
    void testTwoPartsInOneChunk() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "body 1\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part2\n" +
                               '\n' +
                               "body 2\n" +
                               "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(4);
        final Consumer<BodyPart> consumer = part -> {
            if (counter.decrementAndGet() == 3) {
                assertThat(part.headers().get("Content-Id")).contains("part1");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    assertThat(body).isEqualTo("body 1");
                });
            } else {
                assertThat(part.headers().get("Content-Id")).contains("part2");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    assertThat(body).isEqualTo("body 2");
                });
            }
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.error).isNull();
        assertThat(testSubscriber.complete).isTrue();
    }

    @Test
    void testContentAcrossChunks() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "this-is-the-1st-slice-of-the-body\n").getBytes();
        final byte[] chunk2 = ("this-is-the-2nd-slice-of-the-body\n" +
                               "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(2);
        final Consumer<BodyPart> consumer = part -> {
            counter.decrementAndGet();
            assertThat(part.headers().get("Content-Id")).contains("part1");
            final HttpDataAggregator subscriber = new HttpDataAggregator();
            part.content().subscribe(subscriber);
            subscriber.content().thenAccept(body -> {
                counter.decrementAndGet();
                assertThat(body).isEqualTo(
                        "this-is-the-1st-slice-of-the-body\n" +
                        "this-is-the-2nd-slice-of-the-body");
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2)).subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.error).isNull();
        assertThat(testSubscriber.complete).isTrue();
    }

    @Test
    void testMultipleChunksOfPartsBeforeContent() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n").getBytes();
        final byte[] chunk2 = "Content-Type: text/plain\n".getBytes();
        final byte[] chunk3 = "Set-Cookie: bob=alice\n".getBytes();
        final byte[] chunk4 = "Set-Cookie: foo=bar\n".getBytes();
        final byte[] chunk5 = ('\n' +
                               "body 1\n").getBytes();
        final byte[] chunk6 = ("--" + boundary + '\n' +
                               "Content-Id: part2\n").getBytes();
        final byte[] chunk7 = "Content-Type: text/plain\n".getBytes();
        final byte[] chunk8 = "Set-Cookie: bob=anne\n".getBytes();
        final byte[] chunk9 = "Set-Cookie: foo=quz\n".getBytes();
        final byte[] chunk10 = ('\n' +
                                "body 2\n" +
                                "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(4);
        final Consumer<BodyPart> consumer = part -> {
            if (counter.decrementAndGet() == 3) {
                assertThat(part.headers().get("Content-Id")).contains("part1");
                assertThat(part.headers().get("Content-Type")).contains("text/plain");
                assertThat(part.headers().getAll("Set-Cookie")).contains("bob=alice", "foo=bar");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    assertThat(body).isEqualTo("body 1");
                });
            } else {
                assertThat(part.headers().get("Content-Id")).contains("part2");
                assertThat(part.headers().get("Content-Type")).contains("text/plain");
                assertThat(part.headers().getAll("Set-Cookie")).contains("bob=anne", "foo=quz");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    assertThat(body).isEqualTo("body 2");
                });
            }
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2, chunk3, chunk4, chunk5,
                                                  chunk6, chunk7, chunk8, chunk9, chunk10))
                .subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.error).isNull();
        assertThat(testSubscriber.complete).isTrue();
    }

    @Test
    void testMultipleChunksBeforeContent() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n").getBytes();
        final byte[] chunk2 = "Content-Type: text/plain\n".getBytes();
        final byte[] chunk3 = "Set-Cookie: bob=alice\n".getBytes();
        final byte[] chunk4 = "Set-Cookie: foo=bar\n".getBytes();
        final byte[] chunk5 = ('\n' +
                               "body 1\n--" +
                               boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(2);
        final Consumer<BodyPart> consumer = part -> {
            counter.decrementAndGet();
            assertThat(part.headers().get("Content-Id")).contains("part1");
            assertThat(part.headers().get("Content-Type")).contains("text/plain");
            assertThat(part.headers().getAll("Set-Cookie")).contains("bob=alice", "foo=bar");
            final HttpDataAggregator subscriber = new HttpDataAggregator();
            part.content().subscribe(subscriber);
            subscriber.content().thenAccept(body -> {
                counter.decrementAndGet();
                assertThat(body).isEqualTo("body 1");
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2, chunk3, chunk4, chunk5))
                .subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.error).isNull();
        assertThat(testSubscriber.complete).isTrue();
    }

    @Test
    void testMulitiplePartsWithOneByOneSubscriber() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "body 1\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part2\n" +
                               '\n' +
                               "body 2\n" +
                               "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(4);
        final Consumer<BodyPart> consumer = part -> {
            if (counter.decrementAndGet() == 3) {
                assertThat(part.headers().get("Content-Id")).contains("part1");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    assertThat(body).isEqualTo("body 1");
                });
            } else {
                assertThat(part.headers().get("Content-Id")).contains("part2");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    assertThat(body).isEqualTo("body 2");
                });
            }
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.ONE_BY_ONE, consumer);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.error).isNull();
        assertThat(testSubscriber.complete).isTrue();
    }

    @Test
    void testSubscriberCancelAfterOnePart() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "body 1\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part2\n" +
                               '\n' +
                               "body 2\n" +
                               "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(2);
        final Consumer<BodyPart> consumer = part -> {
            if (counter.decrementAndGet() == 1) {
                assertThat(part.headers().get("Content-Id")).contains("part1");
                final HttpDataAggregator subscriber1 = new HttpDataAggregator();
                part.content().subscribe(subscriber1);
                subscriber1.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    assertThat(body).isEqualTo("body 1");
                });
            }
        };

        final BodyPartSubscriber testSubscriber =
                new BodyPartSubscriber(SubscriberType.CANCEL_AFTER_ONE, consumer);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);

        await().untilAtomic(counter, is(0));
    }

    @Test
    void testNoClosingBoundary() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Type: text/xml; charset=UTF-8\n" +
                               "Content-Id: part1\n" +
                               '\n' +
                               "<foo>bar</foo>\n").getBytes();

        final BodyPartSubscriber testSubscriber =
                new BodyPartSubscriber(SubscriberType.CANCEL_AFTER_ONE, null);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);
        assertThat(testSubscriber.complete).isFalse();
        assertThat(testSubscriber.error).isNotNull();
        assertThat(testSubscriber.error.getClass()).isEqualTo(MimeParsingException.class);
        assertThat(testSubscriber.error.getMessage()).isEqualTo("No closing MIME boundary");
    }

    @Test
    void testPartContentSubscriberThrottling() throws InterruptedException {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "body 1.aaaa\n").getBytes();
        final byte[] chunk2 = "body 1.bbbb\n".getBytes();
        final byte[] chunk3 = ("body 1.cccc\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part2\n" +
                               '\n' +
                               "This is the 2nd").getBytes();
        final byte[] chunk4 = ("body.\n" +
                               "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(3);
        final Consumer<BodyPart> consumer = part -> {
            if (counter.decrementAndGet() == 2) {
                assertThat(part.headers().get("Content-Id")).contains("part1");
            }
            part.content().subscribe(new Subscriber<HttpData>() {

                @Override
                public void onSubscribe(Subscription subscription) {
                    subscription.request(1);
                }

                @Override
                public void onNext(HttpData item) {
                    counter.decrementAndGet();
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onComplete() {}
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.ONE_BY_ONE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2, chunk3, chunk4)).subscribe(testSubscriber);
        Thread.sleep(1000);
        await().untilAtomic(counter, is(1));
        assertThat(testSubscriber.error).isNull();
        assertThat(testSubscriber.complete).isFalse();
    }

    @Test
    void testUpstreamError() {
        final MultiPartDecoder decoder = new MultiPartDecoder("boundary");
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, null);
        decoder.subscribe(testSubscriber);
        Multi.<HttpData>error(new IllegalStateException("oops")).subscribe(decoder);
        assertThat(testSubscriber.complete).isFalse();
        assertThat(testSubscriber.error).isNotNull();
        assertThat(testSubscriber.error.getMessage()).isEqualTo("oops");
    }

    @Test
    void testSubcribingMoreThanOnce() {
        final MultiPartDecoder decoder = new MultiPartDecoder("boundary");
        chunksPublisher("foo".getBytes()).subscribe(decoder);
        assertThatThrownBy(() -> chunksPublisher("bar".getBytes()).subscribe(decoder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Subscription already set.");
    }

    /**
     * Types of test subscribers.
     */
    enum SubscriberType {
        INFINITE,
        ONE_BY_ONE,
        CANCEL_AFTER_ONE,
    }

    /**
     * A part test subscriber.
     */
    static class BodyPartSubscriber implements Subscriber<BodyPart> {

        private final SubscriberType subscriberType;
        private final Consumer<BodyPart> consumer;
        private Subscription subcription;
        private Throwable error;
        private boolean complete;

        BodyPartSubscriber(SubscriberType subscriberType, Consumer<BodyPart> consumer) {
            this.subscriberType = subscriberType;
            this.consumer = consumer;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            subcription = subscription;
            if (subscriberType == SubscriberType.INFINITE) {
                subscription.request(Long.MAX_VALUE);
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(BodyPart item) {
            if (consumer == null) {
                return;
            }
            consumer.accept(item);
            if (subscriberType == SubscriberType.ONE_BY_ONE) {
                subcription.request(1);
            } else if (subscriberType == SubscriberType.CANCEL_AFTER_ONE) {
                subcription.cancel();
            }
        }

        @Override
        public void onError(Throwable ex) {
            error = ex;
        }

        @Override
        public void onComplete() {
            complete = true;
        }
    }

    /**
     * Create the parts publisher for the specified boundary and request chunk.
     * @param boundary multipart boundary string
     * @param data data for the chunk
     * @return publisher of body parts
     */
    static Publisher<? extends BodyPart> partsPublisher(String boundary, byte[] data) {
        return partsPublisher(boundary, ImmutableList.of(data));
    }

    /**
     * Create the parts publisher for the specified boundary and request chunks.
     * @param boundary multipart boundary string
     * @param data data for the chunks
     * @return publisher of body parts
     */
    static Publisher<? extends BodyPart> partsPublisher(String boundary, List<byte[]> data) {
        final MultiPartDecoder decoder = new MultiPartDecoder(boundary);
        chunksPublisher(data).subscribe(decoder);
        return decoder;
    }

    /**
     * Build a publisher of {@link HttpData} from a single {@code byte[]}.
     * @param bytes data for the chunk to create
     * @return publisher
     */
    static Publisher<HttpData> chunksPublisher(byte[] bytes) {
        return chunksPublisher(ImmutableList.of(bytes));
    }

    /**
     * Build a publisher of {@link HttpData} from a list of {@code byte[]}.
     * @param data data for the chunks to create
     * @return publisher
     */
    static Publisher<HttpData> chunksPublisher(List<byte[]> data) {
        final HttpData[] chunks = data.stream()
                                      .map(HttpData::copyOf)
                                      .toArray(HttpData[]::new);
        return Multi.just(chunks);
    }
}
