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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import reactor.test.StepVerifier;

class StreamMessagePeekTest {

    @Test
    void peek() {
        final StreamMessage<Integer> source = StreamMessage.of(1, 2, 3, 4, 5);
        final Consumer<Integer> ifEvenExistsThenThrow = x -> {
            if (x % 2 == 0) {
                throw new IllegalArgumentException();
            }
        };
        final StreamMessage<Integer> peeked = source.peek(ifEvenExistsThenThrow);
        StepVerifier.create(peeked)
                    .expectNext(1)
                    .expectError(IllegalArgumentException.class)
                    .verify();
    }

    @Test
    void peekWithType() {
        final StreamMessage<Number> source = StreamMessage.of(0.1, 1, 0.2, 2, 0.3, 3);
        final List<Integer> collected = new ArrayList<>();
        final StreamMessage<Number> peeked = source.peek(collected::add, Integer.class);
        StepVerifier.create(peeked)
                    .expectNext(0.1, 1, 0.2, 2, 0.3, 3)
                    .verifyComplete();
        await().untilAsserted(() -> assertThat(collected).isEqualTo(ImmutableList.of(1, 2, 3)));
    }

    @Test
    void peekError() {
        final IllegalStateException first = new IllegalStateException("1");
        final IllegalStateException second = new IllegalStateException("2");
        final IllegalStateException third = new IllegalStateException("3");
        final StreamMessage<Object> aborted = StreamMessage
                .aborted(first)
                .peekError(error -> assertThat(error).isSameAs(first))
                .mapError(error -> second)
                .peekError(error -> assertThat(error).isSameAs(second))
                .mapError(error -> third);
        StepVerifier.create(aborted)
                    .expectErrorMatches(cause -> cause == third)
                    .verify();

        final StreamMessage<Integer> fixed =
                StreamMessage.of(1, 2, 3, 4)
                             .map(i -> {
                                 if (i < 3) {
                                     return i + 1;
                                 } else {
                                     throw first;
                                 }
                             })
                             .peekError(error -> assertThat(error).isSameAs(first))
                             .mapError(error -> second)
                             .peekError(error -> assertThat(error).isSameAs(second))
                             .mapError(error -> third);
        StepVerifier.create(fixed)
                    .expectNext(2, 3)
                    .expectErrorMatches(cause -> cause == third)
                    .verify();

        final DefaultStreamMessage<Integer> defaultStream = new DefaultStreamMessage<>();
        defaultStream.write(1);
        defaultStream.write(2);
        defaultStream.close(first);
        final StreamMessage<Integer> defaultStream2 =
                defaultStream.peekError(error -> assertThat(error).isSameAs(first))
                             .mapError(error -> second)
                             .peekError(error -> assertThat(error).isSameAs(second))
                             .mapError(error -> third)
                             .map(i -> i + 1)
                             .map(i -> i + 1);
        StepVerifier.create(defaultStream2)
                    .expectNext(3, 4)
                    .expectErrorMatches(cause -> cause == third)
                    .verify();
    }
}
