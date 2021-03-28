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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import com.google.common.collect.ImmutableList;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ConcatStreamTest {

    @Test
    void emptyStream() {
        final StreamMessage<Object> source = StreamMessage.of(StreamMessage.of());
        StepVerifier.create(source).verifyComplete();
    }

    @Test
    void arrayStreamMessage() {
        final StreamMessage<Integer>[] array =
                new StreamMessage[]{ StreamMessage.of(1, 2), StreamMessage.of(3, 4) };
        final List<StreamMessage<Integer>> list =
                ImmutableList.of(StreamMessage.of(1, 2), StreamMessage.of(3, 4));

        final StreamMessage<Integer> fromVarargs =
                StreamMessage.concat(StreamMessage.of(1, 2), StreamMessage.of(3, 4));
        final StreamMessage<Integer> fromArray = StreamMessage.concat(array);
        final StreamMessage<Integer> fromList = StreamMessage.concat(list);

        StepVerifier.create(fromArray).expectNext(1, 2, 3, 4).verifyComplete();
        StepVerifier.create(fromVarargs).expectNext(1, 2, 3, 4).verifyComplete();
        StepVerifier.create(fromList).expectNext(1, 2, 3, 4).verifyComplete();
    }

    @Test
    void arrayPublisher() {
        final Publisher<Integer> source1 = Flux.just(1, 2);
        final Publisher<Integer> source2 = Flux.just(3, 4);
        final Publisher<Integer>[] array = new Publisher[]{ source1, source2 };
        final List<Publisher<Integer>> list = ImmutableList.of(source1, source2);

        final StreamMessage<Integer> fromVarargs = StreamMessage.concat(source1, source2);
        final StreamMessage<Integer> fromArray = StreamMessage.concat(array);
        final StreamMessage<Integer> fromList = StreamMessage.concat(list);

        StepVerifier.create(fromArray).expectNext(1, 2, 3, 4).verifyComplete();
        StepVerifier.create(fromVarargs).expectNext(1, 2, 3, 4).verifyComplete();
        StepVerifier.create(fromList).expectNext(1, 2, 3, 4).verifyComplete();
    }
}
