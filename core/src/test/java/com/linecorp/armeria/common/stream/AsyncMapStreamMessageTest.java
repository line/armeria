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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.UnmodifiableFuture;

import reactor.test.StepVerifier;

class AsyncMapStreamMessageTest {
    @Test
    void mapAsync() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2);
        final StreamMessage<Integer> incremented = streamMessage.mapAsync(
                x -> UnmodifiableFuture.completedFuture(x + 1));

        StepVerifier.create(incremented)
                    .expectNext(2, 3)
                    .verifyComplete();
    }

    @Test
    void mapAsyncFutureCompletesWithException() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1);

        final CompletableFuture<Integer> errorFuture = new CompletableFuture<>();
        final StreamMessage<Integer> willError = streamMessage.mapAsync(
                x -> errorFuture
        );

        final Throwable exception = new RuntimeException();
        errorFuture.completeExceptionally(exception);

        StepVerifier.create(willError)
                    .expectErrorMatches(error -> error == exception)
                    .verify();
    }

    @Test
    void mapAsyncFunctionThrowsException() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(0);
        final StreamMessage<Integer> willError = streamMessage.mapAsync(
                x -> {
                    final int divided = 2 / x;
                    return UnmodifiableFuture.completedFuture(divided);
                }
        );

        StepVerifier.create(willError)
                    .expectError(ArithmeticException.class)
                    .verify();
    }

    @Test
    void mapAsyncFutureIsNull() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1);
        final StreamMessage<Integer> mapsToNull = streamMessage.mapAsync(
                x -> null
        );

        StepVerifier.create(mapsToNull)
                    .expectError(NullPointerException.class)
                    .verify();
    }

    @Test
    void mapAsyncFutureCompletesWithNull() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1);
        final StreamMessage<Integer> mapsToNull = streamMessage.mapAsync(
                x -> UnmodifiableFuture.completedFuture(null)
        );

        StepVerifier.create(mapsToNull)
                    .expectError(NullPointerException.class)
                    .verify();
    }

    @Test
    void mapAsyncDoesntCompleteIfFutureDoesntComplete() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1);
        final StreamMessage<Integer> willNotComplete = streamMessage.mapAsync(
                x -> new CompletableFuture<>()
        );

        StepVerifier.create(willNotComplete).expectNextCount(0).verifyTimeout(Duration.ofMillis(100));
    }

    @Test
    void mapAsyncCompletesWhenFutureCompletes() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1);

        final CompletableFuture<Integer> future = new CompletableFuture<>();
        final StreamMessage<Integer> willNotComplete = streamMessage.mapAsync(
                x -> future
        );

        StepVerifier.create(willNotComplete)
                    .thenRequest(1)
                    .then(() -> future.complete(1))
                    .expectNext(1)
                    .verifyComplete();
    }

    @Test
    void mapAsyncPreservesOrder() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(0, 1, 2);
        final CompletableFuture<Integer> finishFirst = UnmodifiableFuture.completedFuture(2);
        final CompletableFuture<Integer> finishLast = new CompletableFuture<>();
        final CompletableFuture<Integer> finishSecond = new CompletableFuture<>();

        final List<CompletableFuture<Integer>> futures = ImmutableList.of(finishLast, finishFirst,
                                                                          finishSecond);
        final StreamMessage<Integer> shouldPreserveOrder = streamMessage.mapAsync(futures::get);

        StepVerifier.create(shouldPreserveOrder)
                    .thenRequest(3)
                    .then(() -> finishSecond.complete(3))
                    .then(() -> finishLast.complete(1))
                    .expectNext(1, 2, 3)
                    .verifyComplete();
    }

    @Test
    void mapAsyncAbortGoesToUpstream() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(0, 1, 2);
        final StreamMessage<Integer> mapped = streamMessage.mapAsync(CompletableFuture::completedFuture);

        mapped.abort();
        assertThat(streamMessage.isComplete()).isTrue();
    }

    @Test
    void mapAsyncAbortGoesToUpstreamWithException() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(0, 1, 2);
        final StreamMessage<Integer> mapped = streamMessage.mapAsync(CompletableFuture::completedFuture);

        mapped.abort(new RuntimeException());
        StepVerifier.create(streamMessage).verifyError(RuntimeException.class);
    }

    @Test
    void mapParallelPublishesEagerly() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(0, 1, 2);
        final CompletableFuture<Integer> finishFirst = CompletableFuture.completedFuture(2);
        final CompletableFuture<Integer> finishLast = new CompletableFuture<>();
        final CompletableFuture<Integer> finishSecond = new CompletableFuture<>();

        final List<CompletableFuture<Integer>> futures = ImmutableList.of(finishLast, finishSecond,
                                                                          finishFirst);
        final StreamMessage<Integer> shouldNotPreserveOrder = streamMessage.mapParallel(futures::get);

        StepVerifier.create(shouldNotPreserveOrder)
                    .expectNext(2)
                    .then(() -> finishSecond.complete(3))
                    .then(() -> finishLast.complete(1))
                    .expectNext(3, 1)
                    .verifyComplete();
    }

    @Test
    void mapParallelDoesntPublishPastLimit() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(0, 1, 2);
        final CompletableFuture<Integer> finishFirst = CompletableFuture.completedFuture(2);
        final CompletableFuture<Integer> finishLast = new CompletableFuture<>();
        final CompletableFuture<Integer> finishSecond = new CompletableFuture<>();

        final List<CompletableFuture<Integer>> futures = ImmutableList.of(finishLast, finishSecond,
                                                                          finishFirst);
        final StreamMessage<Integer> shouldNotPreserveOrder = streamMessage.mapParallel(futures::get, 2);

        StepVerifier.create(shouldNotPreserveOrder)
                    .then(() -> finishSecond.complete(3))
                    .expectNext(3)
                    .then(() -> finishLast.complete(1))
                    .expectNext(2, 1)
                    .verifyComplete();
    }
}
