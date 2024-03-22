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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.StepVerifierOptions;

class FlatMapStreamMessageTest {
    @Test
    void flatMap() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 3);
        final Function<Integer, StreamMessage<Integer>> function = i -> StreamMessage.of(i, i + 1);

        final Set<Integer> pendingItems = new HashSet<>(Arrays.asList(1, 2, 3, 4));
        final StreamMessage<Integer> mappedStream = streamMessage.flatMap(function);

        StepVerifier.create(mappedStream)
                    .recordWith(HashSet::new)
                    .expectNextCount(4)
                    .expectRecordedMatches(c -> c.equals(pendingItems))
                    .verifyComplete();
    }

    @Test
    void flatMapAllowsReordering() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2);
        final CompletableFuture<Integer> future = new CompletableFuture<>();

        final Function<Integer, StreamMessage<Integer>> function = x -> x == 1 ? StreamMessage.of(x).mapAsync(
                y -> future) : StreamMessage.of(x);

        final StreamMessage<Integer> mappedStream = streamMessage.flatMap(function);

        StepVerifier.create(mappedStream)
                    .expectNext(2)
                    .then(() -> future.complete(3))
                    .expectNext(3)
                    .verifyComplete();
    }

    @Test
    void flatMapPropagatesError() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1);
        final Function<Integer, StreamMessage<Integer>> function = y -> StreamMessage.aborted(
                new RuntimeException());

        final StreamMessage<Integer> mappedStream = streamMessage.flatMap(function);

        StepVerifier.create(mappedStream)
                    .thenRequest(1)
                    .verifyError(RuntimeException.class);
    }

    @Test
    void flatMapIsAssociative() throws Exception {
        final Function<Integer, StreamMessage<Integer>> h = x -> StreamMessage.of(x + 1);
        final Function<Integer, StreamMessage<Integer>> g = x -> StreamMessage.of(x * 2);

        final StreamMessage<Integer> leftSide = StreamMessage.of(1,2,3).flatMap(g).flatMap(h);
        final StreamMessage<Integer> rightSide = StreamMessage.of(1,2,3).flatMap(x -> g.apply(x).flatMap(h));

        final Set<Integer> leftSideValues = new HashSet<>(leftSide.collect().get());
        final Set<Integer> rightSideValues = new HashSet<>(rightSide.collect().get());

        assertEquals(leftSideValues, rightSideValues);
    }

    @Test
    void completionFutureIsCompleteWhenStreamIsEmpty() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3);
        final Function<Integer, StreamMessage<Integer>> function = i -> StreamMessage.of(i, i + 1);
        final StreamMessage<Integer> mappedStream = streamMessage.flatMap(function);

        StepVerifier.create(mappedStream)
                    .expectNextCount(6)
                    .verifyComplete();

        await().untilAsserted(() -> assertThat(mappedStream.whenComplete()).isDone());
    }

    @Test
    void shouldHandleImmediatelyCompletingStream() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3);
        final Function<Integer, StreamMessage<Integer>> function = StreamMessage::of;
        final StreamMessage<Integer> mappedStream = streamMessage.flatMap(function);

        StepVerifier.create(mappedStream)
                    .thenRequest(100L)
                    .expectNextCount(3)
                    .verifyComplete();
        await().untilAsserted(() -> assertThat(mappedStream.whenComplete()).isDone());
    }

    @Test
    void shouldProcessAllWhenMoreElementsThanMaxConcurrency() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(10, 20, 30);
        final Function<Integer, StreamMessage<Integer>> function = num -> StreamMessage.of(Flux.range(num, 3));
        final StreamMessage<Integer> mappedStream = streamMessage.flatMap(function, 1);

        StepVerifier.create(mappedStream)
                    .thenRequest(100L)
                    .thenConsumeWhile(n -> true)
                    .verifyComplete();
    }

    @Test
    void flatMapIsNotCompleteBeforeDownstreamReceivesAllValues() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        final Function<Integer, StreamMessage<Integer>> function = StreamMessage::of;
        final StreamMessage<Integer> mappedStream = streamMessage.flatMap(function);

        StepVerifier.create(mappedStream, StepVerifierOptions.create().initialRequest(0L))
                    .thenRequest(1)
                    .expectNext(1)
                    .verifyTimeout(Duration.ofMillis(20));
        await().untilAsserted(() -> assertThat(mappedStream.isComplete()).isTrue());
    }

    @Test
    void flatMapIsCompleteAfterLastElementsAreRequested() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2);
        final Function<Integer, StreamMessage<Integer>> function = StreamMessage::of;
        final StreamMessage<Integer> mappedStream = streamMessage.flatMap(function);

        StepVerifier.create(mappedStream, StepVerifierOptions.create().initialRequest(0L))
                    .thenRequest(1)
                    .expectNext(1)
                    .thenAwait(Duration.ofMillis(20))
                    .thenRequest(1)
                    .expectNext(2)
                    .verifyComplete();
    }
}
