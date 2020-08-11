/*
 * Copyright 2020 LINE Corporation
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
 * Copyright (c)  2020 Oracle and/or its affiliates. All rights reserved.
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
 *
 */

package com.linecorp.armeria.common.multipart;

import static com.linecorp.armeria.common.multipart.StreamMessages.concat;
import static com.linecorp.armeria.common.multipart.StreamMessages.onCompleteResumeWith;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class StreamMessageOnCompleteResumeWithTest {

    // Forked from https://github.com/oracle/helidon/blob/0325cae20e68664da0f518ea2d803b9dd211a7b5/common
    // /reactive/src/test/java/io/helidon/common/reactive/MultiOnCompleteResumeWithTest.java

    @Test
    void fallbackToError() {
        final StreamMessage<Integer> source =
                onCompleteResumeWith(concat(StreamMessage.of()),
                                     new PublisherBasedStreamMessage<>(
                                             Flux.error(new IllegalArgumentException())));

        StepVerifier.create(source)
                    .expectError(IllegalArgumentException.class)
                    .verify();
    }

    @Test
    void errorSource() {
        final StreamMessage<Integer> source =
                onCompleteResumeWith(new PublisherBasedStreamMessage<>(
                                             Flux.error(new IOException())),
                                     StreamMessage.of(1));

        StepVerifier.create(source)
                    .expectNextCount(0)
                    .verifyError(IOException.class);
    }

    @Test
    void emptyFallback() {
        final StreamMessage<Object> source = onCompleteResumeWith(StreamMessage.of(), StreamMessage.of());
        StepVerifier.create(source)
                    .verifyComplete();
    }

    @Test
    void appendAfterItems() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>(Long.MAX_VALUE);

        final StreamMessage<Integer> source =
                onCompleteResumeWith(
                        concat(Flux.range(1, 3),
                               Flux.empty()),
                        new PublisherBasedStreamMessage<>(Flux.range(4, 2)));

        StepVerifier.create(source)
                    .expectNext(1, 2, 3, 4, 5)
                    .verifyComplete();
    }

    @Test
    void appendAfterItemsBackpressure() {
        final StreamMessage<Integer> source =
                onCompleteResumeWith(concat(Flux.range(1, 3), Flux.empty()),
                                     Flux.range(4, 2));

        StepVerifier.create(source)
                    .thenRequest(3)
                    .expectNext(1, 2, 3)
                    .thenRequest(2)
                    .expectNext(4, 5)
                    .verifyComplete();
    }

    @Test
    void multipleAppendAfterItemsBackpressure() {
        final StreamMessage<Integer> source =
                onCompleteResumeWith(
                        onCompleteResumeWith(
                                concat(Flux.empty(), Flux.just(1, 2, 3)), Flux.just(4, 5)),
                        Flux.just(6, 7));

        StepVerifier.create(source)
                    .thenRequest(3)
                    .expectNext(1, 2, 3)
                    .thenRequest(2)
                    .expectNext(4, 5)
                    .thenRequest(1)
                    .expectNext(6)
                    .thenRequest(1)
                    .expectNext(7)
                    .verifyComplete();
    }

    @Test
    void appendChain() {
        final StreamMessage<Integer> source =
                onCompleteResumeWith(
                        onCompleteResumeWith(
                                onCompleteResumeWith(
                                        onCompleteResumeWith(concat(Flux.just(1, 2, 3)), StreamMessage.of(4)),
                                        StreamMessage.of(5)),
                                StreamMessage.of(6, 7)),
                        StreamMessage.of(8));

        StepVerifier.create(source)
                    .thenRequest(Long.MAX_VALUE)
                    .expectNext(1, 2, 3, 4, 5, 6, 7, 8)
                    .expectComplete()
                    .verify();
    }
}
