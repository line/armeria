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

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

class MappableStreamMessageTest {

    @Test
    void composedFilter() {

        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5, 6, 7, 8);
        final StreamMessage<Integer> even = streamMessage.filter(x -> x % 2 == 0);
        final StreamMessage<Integer> biggerThan4 = even.filter(x -> x > 4);
        final MappableStreamMessage<Integer, Integer> cast =
                (MappableStreamMessage<Integer, Integer>) biggerThan4;

        // Should keep the original StreamMessage
        assertThat(cast.upstream()).isSameAs(streamMessage);

        StepVerifier.create(biggerThan4)
                    .thenRequest(1)
                    .expectNext(4)
                    .thenRequest(1)
                    .expectNext(8)
                    .verifyComplete();
    }

    @Test
    void composeFilterAndMap() {
        final StreamMessage<Integer> streamMessage = StreamMessage.of(1, 2, 3, 4, 5, 6);
        final StreamMessage<Integer> even = streamMessage.filter(x -> x % 2 == 0);
        final StreamMessage<Integer> doubled = even.map(x -> x * 2);
        final MappableStreamMessage<Integer, Integer> cast = (MappableStreamMessage<Integer, Integer>) doubled;
        // Should keep the original StreamMessage
        assertThat(cast.upstream()).isSameAs(streamMessage);

        StepVerifier.create(doubled)
                    .expectNext(4, 8, 12)
                    .verifyComplete();
    }
}
