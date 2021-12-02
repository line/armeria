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

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

class AsyncMapStreamMessageTest {
    @Test
    void mapAsync() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2);
        final StreamMessage<Integer> incremented = streamMessage.mapAsync(
                x -> CompletableFuture.completedFuture(x + 1));

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
                    return CompletableFuture.completedFuture(divided);
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
                x -> CompletableFuture.completedFuture(null)
        );

        StepVerifier.create(mapsToNull)
                    .expectError(NullPointerException.class)
                    .verify();
    }
}
