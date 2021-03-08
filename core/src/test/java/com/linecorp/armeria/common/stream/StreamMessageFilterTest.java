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

import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

class StreamMessageFilterTest {
    @Test
    void filter() {
        final StreamMessage<Integer> source = StreamMessage.of(1, 2, 3, 4, 5);
        final Predicate<Integer> even = x -> x % 2 == 0;
        final StreamMessage<Integer> filtered = source.filter(even);
        StepVerifier.create(filtered)
                    .expectNext(2, 4)
                    .verifyComplete();
    }
}
