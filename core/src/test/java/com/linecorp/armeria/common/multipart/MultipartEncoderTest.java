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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.primitives.Bytes;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.stream.StreamMessage;

import reactor.core.publisher.Flux;
import reactor.test.publisher.PublisherProbe;

/**
 * Test {@link MultipartEncoder}.
 */
class MultipartEncoderTest {

    // Forked from https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/media
    // /multipart/src/test/java/io/helidon/media/multipart/MultiPartEncoderTest.java

    @Test
    void testEncodeOnePart() throws Exception {
        final String boundary = "boundary";
        final String message = encodeParts(boundary,
                                           BodyPart.builder().content("part1").build());
        assertThat(message).isEqualTo("--" + boundary + "\r\n" +
                                      "content-type:text/plain\r\n" +
                                      "\r\n" +
                                      "part1\r\n" +
                                      "--" + boundary + "--");
    }

    @Test
    void testEncodeOnePartWithHeaders() throws Exception {
        final String boundary = "boundary";
        final BodyPart part1 = BodyPart.builder()
                                       .headers(HttpHeaders.builder()
                                                           .contentType(MediaType.PLAIN_TEXT)
                                                           .build())
                                       .content("part1")
                                       .build();
        final String message = encodeParts(boundary, part1);
        assertThat(message).isEqualTo("--" + boundary + "\r\n" +
                                      "content-type:text/plain\r\n" +
                                      "\r\n" +
                                      "part1\r\n" +
                                      "--" + boundary + "--");
    }

    @Test
    void testEncodeTwoParts() throws Exception {
        final String boundary = "boundary";
        final String message = encodeParts(boundary,
                                           BodyPart.builder()
                                                   .content("part1")
                                                   .build(),
                                           BodyPart.builder()
                                                   .content("part2")
                                                   .build());
        assertThat(message).isEqualTo("--" + boundary + "\r\n" +
                                      "content-type:text/plain\r\n" +
                                      "\r\n" +
                                      "part1\r\n" +
                                      "--" + boundary + "\r\n" +
                                      "content-type:text/plain\r\n" +
                                      "\r\n" +
                                      "part2\r\n" +
                                      "--" + boundary + "--");
    }

    @Test
    void testRequests() throws Exception {
        final MultipartEncoder enc = new MultipartEncoder("boundary");
        final BodyPart[] parts = LongStream.range(1, 500)
                                           .mapToObj(i -> BodyPart.builder()
                                                                  .content("part" + i)
                                                                  .build())
                                           .toArray(BodyPart[]::new);

        StreamMessage.of(parts).subscribe(enc);
        final AtomicInteger counter = new AtomicInteger(3);
        final Subscriber<HttpData> subscriber = new Subscriber<HttpData>() {

            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(3L);
            }

            @Override
            public void onNext(HttpData item) {
                counter.getAndDecrement();
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable throwable) {
            }
        };

        enc.subscribe(subscriber);
        await().untilAtomic(counter, Matchers.is(0));
    }

    @Test
    void testSubscribingMoreThanOnce() {
        final MultipartEncoder encoder = new MultipartEncoder("boundary");
        Flux.<BodyPart>empty().subscribe(encoder);
        final PublisherProbe<BodyPart> probe = PublisherProbe.of(Flux.empty());
        probe.flux().subscribe(encoder);
        probe.assertWasCancelled();
    }

    @Test
    void testUpstreamError() {
        final MultipartEncoder decoder = new MultipartEncoder("boundary");
        Flux.<BodyPart>error(new IllegalStateException("oops")).subscribe(decoder);
        final HttpDataAggregator subscriber = new HttpDataAggregator();
        decoder.subscribe(subscriber);
        final CompletableFuture<String> future = subscriber.content().toCompletableFuture();

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oops");
    }

    @Test
    void testPartContentPublisherError() {
        final MultipartEncoder encoder = new MultipartEncoder("boundary");
        final HttpDataAggregator subscriber = new HttpDataAggregator();
        encoder.subscribe(subscriber);

        Flux.just(BodyPart.builder()
                           .content(Flux.error(new IllegalStateException("oops")))
                           .build())
             .subscribe(encoder);
        final CompletableFuture<String> future = subscriber.content().toCompletableFuture();

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oops");
    }

    private static String encodeParts(String boundary, BodyPart... parts) throws Exception {
        final MultipartEncoder encoder = new MultipartEncoder(boundary);
        StreamMessage.of(parts).subscribe(encoder);
        return Flux.from(encoder)
                   .map(HttpData::array)
                   .reduce(Bytes::concat)
                   .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                   .block(Duration.ofSeconds(10));
    }

    /**
     * A subscriber of data chunk that accumulates bytes to a single String.
     */
    static final class HttpDataAggregator implements Subscriber<HttpData> {

        private final List<HttpData> dataList = new ArrayList<>();
        private final CompletableFuture<String> future = new CompletableFuture<>();

        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(HttpData item) {
            dataList.add(item);
        }

        @Override
        public void onError(Throwable ex) {
            future.completeExceptionally(ex);
        }

        @Override
        public void onComplete() {
            final String content = dataList.stream().map(HttpData::array)
                                           .reduce(Bytes::concat)
                                           .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                                           .orElse(null);
            future.complete(content);
        }

        CompletionStage<String> content() {
            return future;
        }
    }
}
