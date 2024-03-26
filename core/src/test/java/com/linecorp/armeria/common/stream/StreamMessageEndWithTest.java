/*
 * Copyright 2023 LINE Corporation
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

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

class StreamMessageEndWithTest {

    @Test
    void endWith() {
        final StreamMessage<Integer> endWith = StreamMessage
                .of(1, 2, 3, 4, 5)
                .endWith(th -> 100);

        StepVerifier.create(endWith)
                    .expectNext(1, 2, 3, 4, 5, 100)
                    .expectComplete()
                    .verify();
    }

    @Test
    void endWith_aborted() {
        final StreamMessage<Integer> source = StreamMessage.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        final StreamMessage<Integer> aborted = source
                .peek(i -> {
                    if (i > 5) {
                        source.abort();
                    }
                });
        final StreamMessage<Integer> endWith = aborted
                .endWith(th -> {
                   if (th instanceof AbortedStreamException) {
                       return 100;
                   }
                   return -1;
                });

        StepVerifier.create(endWith)
                    .expectNext(1, 2, 3, 4, 5, 100)
                    .expectComplete()
                    .verify();
    }

    @Test
    void endWith_thrown() {
        final StreamMessage<Integer> source = StreamMessage.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        final StreamMessage<Integer> thrown = source
                .map(i -> {
                    if (i > 5) {
                        throw new RuntimeException();
                    }
                    return i;
                });
        final StreamMessage<Integer> endWith = thrown
                .endWith(th -> {
                    if (th instanceof AbortedStreamException) {
                        return 100;
                    }
                    return -1;
                });

        StepVerifier.create(endWith)
                    .expectNext(1, 2, 3, 4, 5, -1)
                    .expectComplete()
                    .verify();
    }

    @Test
    void endWith_not_thrown() {
        final StreamMessage<Integer> source = StreamMessage.of(1, 2, 3, 4, 5);
        final StreamMessage<Integer> endWith = source
                .endWith(th -> {
                    if (th instanceof AbortedStreamException) {
                        return 999;
                    }
                    if (th == null) {
                        return 100;
                    }
                    return -1;
                });

        StepVerifier.create(endWith)
                    .expectNext(1, 2, 3, 4, 5, 100)
                    .expectComplete()
                    .verify();
    }
}
