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

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

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
                    .verifyThenAssertThat();
    }

    @Test
    void peekWithType() {
        final StreamMessage<Number> source = StreamMessage.of(0.1, 1, 0.2, 2);
        final Consumer<Integer> ifEvenExistsThenThrow = x -> {
            if (x % 2 == 0) {
                throw new IllegalArgumentException();
            }
        };
        final StreamMessage<Number> peeked = source.peek(ifEvenExistsThenThrow, Integer.class);
        StepVerifier.create(peeked)
                    .expectNext(0.1, 1, 0.2)
                    .expectError(IllegalArgumentException.class)
                    .verifyThenAssertThat();
    }
}
