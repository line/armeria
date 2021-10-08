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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.multipart.MultipartEncoderTest.HttpDataAggregator;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.buffer.ByteBufAllocator;
import reactor.core.publisher.Flux;

/**
 * Tests {@link MultipartDecoder}.
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
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> headers = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            counter.getAndDecrement();
            headers.add(part.headers().get("Content-Id"));
            final HttpDataAggregator subscriber = new HttpDataAggregator();
            part.content().subscribe(subscriber);
            subscriber.content().thenAccept(body -> {
                counter.getAndDecrement();
                bodies.add(body);
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);
        await().forever().untilAtomic(counter, is(0));
        testSubscriber.completionFuture.join();
        assertThat(headers).containsExactly("part1");
        assertThat(bodies).containsExactly("body 1");
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
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> headers = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            headers.add(part.headers().get("Content-Id"));
            if (counter.decrementAndGet() == 3) {
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    bodies.add(body);
                });
            } else {
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    bodies.add(body);
                });
            }
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.ONE_BY_ONE, consumer);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.completionFuture).isDone();
        assertThat(headers).containsExactly("part1", "part2");
        assertThat(bodies).containsExactly("body 1", "body 2");
    }

    @Test
    void testFourPartsInOneChunk() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "body 1\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part2\n" +
                               '\n' +
                               "body 2\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part3\n" +
                               '\n' +
                               "body 3\n" +
                               "--" + boundary + '\n' +
                               "Content-Id: part4\n" +
                               '\n' +
                               "body 4\n").getBytes();
        final byte[] chunk2 = ("--" + boundary + '\n' +
                               "Content-Id: part5\n" +
                               '\n' +
                               "body 5\n" +
                               "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(5);
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> contentIds = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            contentIds.add(part.headers().get("Content-Id"));
            final HttpDataAggregator subscriber = new HttpDataAggregator();
            part.content().subscribe(subscriber);
            subscriber.content().thenAccept(body -> {
                bodies.add(body);
                counter.decrementAndGet();
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.ONE_BY_ONE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2)).subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.completionFuture).isDone();
        assertThat(contentIds).containsExactly("part1", "part2", "part3", "part4", "part5");
        assertThat(bodies).containsExactly("body 1", "body 2", "body 3", "body 4", "body 5");
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
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            counter.decrementAndGet();
            assertThat(part.headers().get("Content-Id")).contains("part1");
            final HttpDataAggregator subscriber = new HttpDataAggregator();
            part.content().subscribe(subscriber);
            subscriber.content().thenAccept(body -> {
                counter.decrementAndGet();
                bodies.add(body);
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2)).subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.completionFuture).isDone();
        assertThat(bodies).containsExactly(
                "this-is-the-1st-slice-of-the-body\n" +
                "this-is-the-2nd-slice-of-the-body");
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
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            if (counter.decrementAndGet() == 3) {
                assertThat(part.headers().get("Content-Id")).contains("part1");
                assertThat(part.headers().get("Content-Type")).contains("text/plain");
                assertThat(part.headers().getAll("Set-Cookie")).contains("bob=alice", "foo=bar");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    bodies.add(body);
                });
            } else {
                assertThat(part.headers().get("Content-Id")).contains("part2");
                assertThat(part.headers().get("Content-Type")).contains("text/plain");
                assertThat(part.headers().getAll("Set-Cookie")).contains("bob=anne", "foo=quz");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    bodies.add(body);
                });
            }
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2, chunk3, chunk4, chunk5,
                                                  chunk6, chunk7, chunk8, chunk9, chunk10))
                .subscribe(testSubscriber);
        await().forever().untilAtomic(counter, is(0));
        assertThat(testSubscriber.completionFuture).isDone();
        assertThat(bodies).containsExactly("body 1", "body 2");
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
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            counter.decrementAndGet();
            assertThat(part.headers().get("Content-Id")).contains("part1");
            assertThat(part.headers().get("Content-Type")).contains("text/plain");
            assertThat(part.headers().getAll("Set-Cookie")).contains("bob=alice", "foo=bar");
            final HttpDataAggregator subscriber = new HttpDataAggregator();
            part.content().subscribe(subscriber);
            subscriber.content().thenAccept(body -> {
                bodies.add(body);
                counter.decrementAndGet();
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2, chunk3, chunk4, chunk5))
                .subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.completionFuture).isDone();
        assertThat(bodies).containsExactly("body 1");
    }

    @Test
    void testMultiplePartsWithOneByOneSubscriber() {
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
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            if (counter.decrementAndGet() == 3) {
                assertThat(part.headers().get("Content-Id")).contains("part1");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    bodies.add(body);
                });
            } else {
                assertThat(part.headers().get("Content-Id")).contains("part2");
                final HttpDataAggregator subscriber = new HttpDataAggregator();
                part.content().subscribe(subscriber);
                subscriber.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    bodies.add("body 2");
                });
            }
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.ONE_BY_ONE, consumer);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);
        await().forever().untilAtomic(counter, is(0));
        assertThat(testSubscriber.completionFuture).isDone();
        assertThat(bodies).containsExactly("body 1", "body 2");
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
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            if (counter.decrementAndGet() == 1) {
                assertThat(part.headers().get("Content-Id")).contains("part1");
                final HttpDataAggregator subscriber1 = new HttpDataAggregator();
                part.content().subscribe(subscriber1);
                subscriber1.content().thenAccept(body -> {
                    counter.decrementAndGet();
                    subscription.cancel();
                    bodies.add(body);
                });
            }
        };

        final BodyPartSubscriber testSubscriber =
                new BodyPartSubscriber(SubscriberType.REQUEST_MANUALLY, consumer);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);

        await().untilAtomic(counter, is(0));
        assertThat(bodies).containsExactly("body 1");
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
                new BodyPartSubscriber(SubscriberType.ONE_BY_ONE, null);
        partsPublisher(boundary, chunk1).subscribe(testSubscriber);
        assertThatThrownBy(testSubscriber.completionFuture::join)
                .hasCauseInstanceOf(MimeParsingException.class)
                .hasMessageContaining("No closing MIME boundary");
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
        final List<String> headers = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (bodyPartsSubscription, part) -> {
            if (counter.decrementAndGet() == 2) {
                headers.add(part.headers().get("Content-Id"));
            }
            part.content().subscribe(new Subscriber<HttpData>() {

                @Override
                public void onSubscribe(Subscription subscription) {
                    subscription.request(1);
                }

                @Override
                public void onNext(HttpData item) {
                    counter.decrementAndGet();
                    bodyPartsSubscription.cancel();
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onComplete() {
                }
            });
        };

        final BodyPartSubscriber testSubscriber =
                new BodyPartSubscriber(SubscriberType.REQUEST_MANUALLY, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2, chunk3, chunk4)).subscribe(testSubscriber);
        Thread.sleep(1000);
        await().untilAtomic(counter, is(1));
        assertThat(testSubscriber.completionFuture).isNotDone();
        assertThat(headers).containsExactly("part1");
    }

    @Test
    void testUpstreamError() {
        final Flux<HttpData> source = Flux.error(new IllegalStateException("oops"));
        final MultipartDecoder decoder = new MultipartDecoder(StreamMessage.of(source), "boundary",
                                                              ByteBufAllocator.DEFAULT);
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, null);
        decoder.subscribe(testSubscriber);

        assertThatThrownBy(testSubscriber.completionFuture::join)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oops");
    }

    @Test
    void testUpstreamErrorDuringBody() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "this-is-the-1st-slice-of-the-body\n").getBytes();
        final byte[] chunk2 = "this-is-the-2nd-slice-of-the-body\n".getBytes();
        final MultipartDecoder decoder =
                new MultipartDecoder(
                        StreamMessage.of(Flux.from(chunksPublisher(ImmutableList.of(chunk1, chunk2)))
                                             .concatWith(Flux.error(new IllegalStateException("oops")))),
                        boundary,
                        ByteBufAllocator.DEFAULT);

        final AtomicInteger counter = new AtomicInteger(2);
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            counter.decrementAndGet();
            final HttpDataAggregator subscriber = new HttpDataAggregator();
            part.content().subscribe(subscriber);
            subscriber.content().exceptionally(throwable -> {
                thrown.set(throwable);
                counter.decrementAndGet();
                return null;
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        decoder.subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThatThrownBy(testSubscriber.completionFuture::join)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oops");
        assertThat(thrown.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oops");
    }

    @Test
    void testBodyPartSubscriberCancelled() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "this-is-the-1st-slice-of-the-body\n").getBytes();
        final byte[] chunk2 = "this-is-the-2nd-slice-of-the-body\n".getBytes();
        final byte[] chunk3 = ("this-is-the-3rd-slice-of-the-body\n" +
                               "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(2);
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            counter.decrementAndGet();
            final StreamMessage<HttpData> content = part.content();
            content.subscribe(new Subscriber<HttpData>() {
                @Nullable
                private Subscription subscription;

                @Override
                public void onSubscribe(Subscription subscription) {
                    this.subscription = subscription;
                    subscription.request(1);
                }

                @Override
                public void onNext(HttpData httpData) {
                    subscription.cancel();
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onComplete() {
                }
            });
            content.whenComplete().exceptionally(throwable -> {
                assertThat(throwable).isInstanceOf(CancelledSubscriptionException.class);
                counter.decrementAndGet();
                return null;
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.INFINITE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2, chunk3)).subscribe(testSubscriber);
        await().untilAtomic(counter, is(0));
        assertThat(testSubscriber.completionFuture).isDone();
    }

    @Test
    void testSomeBodyPartSubscribersCancelled() {
        final String boundary = "boundary";
        final byte[] chunk1 = ("--" + boundary + '\n' +
                               "Content-Id: part1\n" +
                               '\n' +
                               "this-is-the-1st-slice-of-the-body\n").getBytes();
        final byte[] chunk2 = "this-is-the-2nd-slice-of-the-body\n".getBytes();
        final byte[] chunk3 = "this-is-the-3rd-slice-of-the-body\n".getBytes();
        final byte[] chunk4 = ("--" + boundary + '\n' +
                               "Content-Id: part2\n" +
                               '\n' +
                               "body 2\n").getBytes();
        final byte[] chunk5 = ("--" + boundary + '\n' +
                               "Content-Id: part3\n" +
                               '\n' +
                               "this-is-the-1st-slice-of-the-body3\n").getBytes();
        final byte[] chunk6 = "this-is-the-2nd-slice-of-the-body3\n".getBytes();
        final byte[] chunk7 = ("--" + boundary + '\n' +
                               "Content-Id: part4\n" +
                               '\n' +
                               "body 4\n" +
                               "--" + boundary + "--").getBytes();

        final AtomicInteger counter = new AtomicInteger(4);
        final AtomicInteger completeCounter = new AtomicInteger(4);
        final List<String> headers = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final BiConsumer<Subscription, BodyPart> consumer = (subscription, part) -> {
            final int remain = counter.decrementAndGet();
            headers.add(part.headers().get("Content-Id"));
            final StreamMessage<HttpData> content = part.content();
            content.subscribe(new Subscriber<HttpData>() {
                @Nullable
                private Subscription subscription;

                @Override
                public void onSubscribe(Subscription subscription) {
                    this.subscription = subscription;
                    subscription.request(1);
                }

                @Override
                public void onNext(HttpData httpData) {
                    bodies.add(httpData.toStringUtf8());
                    // Skip 2nd Item in 1st and 2nd Multipart.
                    if (remain == 3 || remain == 1) {
                        subscription.cancel();
                    } else {
                        subscription.request(1);
                    }
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onComplete() {
                }
            });
            content.whenComplete().whenComplete((unused, throwable) -> {
                completeCounter.decrementAndGet();
            });
        };
        final BodyPartSubscriber testSubscriber = new BodyPartSubscriber(SubscriberType.ONE_BY_ONE, consumer);
        partsPublisher(boundary, ImmutableList.of(chunk1, chunk2, chunk3, chunk4, chunk5, chunk6, chunk7))
                .subscribe(testSubscriber);
        await().untilAtomic(completeCounter, is(0));
        assertThat(testSubscriber.completionFuture).isDone();
        assertThat(headers).containsExactly("part1", "part2", "part3", "part4");
        assertThat(bodies).containsExactly("this-is-the-1st-slice-o", "body 2",
                                           "this-is-the-1st-slice-of", "body 4");
    }

    /**
     * Types of test subscribers.
     */
    private enum SubscriberType {
        INFINITE,
        ONE_BY_ONE,
        REQUEST_MANUALLY,
    }

    /**
     * A part test subscriber.
     */
    private static class BodyPartSubscriber implements Subscriber<BodyPart> {

        private final SubscriberType subscriberType;
        private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        @Nullable
        private final BiConsumer<Subscription, BodyPart> consumer;
        @Nullable
        private Subscription subscription;

        BodyPartSubscriber(SubscriberType subscriberType,
                           @Nullable BiConsumer<Subscription, BodyPart> consumer) {
            this.subscriberType = subscriberType;
            this.consumer = consumer;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
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
            consumer.accept(subscription, item);
            if (subscriberType == SubscriberType.ONE_BY_ONE) {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable ex) {
            completionFuture.completeExceptionally(ex);
        }

        @Override
        public void onComplete() {
            completionFuture.complete(null);
        }
    }

    /**
     * Create the parts publisher for the specified boundary and request chunk.
     * @param boundary multipart boundary string
     * @param data data for the chunk
     * @return publisher of body parts
     */
    private static Publisher<? extends BodyPart> partsPublisher(String boundary, byte[] data) {
        return partsPublisher(boundary, ImmutableList.of(data));
    }

    /**
     * Create the parts publisher for the specified boundary and request chunks.
     * @param boundary multipart boundary string
     * @param data data for the chunks
     * @return publisher of body parts
     */
    private static Publisher<? extends BodyPart> partsPublisher(String boundary, List<byte[]> data) {
        final Publisher<HttpData> source = chunksPublisher(data);
        return new MultipartDecoder(StreamMessage.of(source), boundary, ByteBufAllocator.DEFAULT);
    }

    /**
     * Build a publisher of {@link HttpData} from a list of {@code byte[]}.
     * @param data data for the chunks to create
     * @return publisher
     */
    private static Publisher<HttpData> chunksPublisher(List<byte[]> data) {
        final HttpData[] chunks = data.stream()
                                      .map(HttpData::copyOf)
                                      .toArray(HttpData[]::new);
        return Flux.just(chunks);
    }
}
