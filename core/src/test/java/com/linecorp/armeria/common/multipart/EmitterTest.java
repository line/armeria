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
 * Copyright (c)  2020 Oracle and/or its affiliates.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class EmitterTest {

    // Forked from https://github.com/oracle/helidon/blob/66200b545a0ace1a28ef8f750112146969f9ee7d/common/reactive/src/test/java/io/helidon/common/reactive/EmitterTest.java

    private static final Integer[] EMPTY_INTEGERS = new Integer[0];

    @Test
    void testOnEmitCallback() {
        final List<Integer> intercepted = new ArrayList<>();

        final List<Integer> data = IntStream.range(0, 10)
                                            .boxed()
                                            .collect(toImmutableList());

        final BufferedEmittingPublisher<Integer> emitter =
                new BufferedEmittingPublisher<>(null, intercepted::add);

        final TestSubscriber<Integer> subscriber = new TestSubscriber<>();
        emitter.subscribe(subscriber);

        assertThat(intercepted)
                .as("onEmit callback executed before first emit")
                .isEmpty();

        data.forEach(emitter::emit);

        assertThat(intercepted)
                .as("onEmit callback executed before first request")
                .isEmpty();

        subscriber.request1()
                  .assertValues(0);

        assertThat(intercepted)
                .as("onEmit callback should have been executed exactly once")
                .hasSize(1);

        final List<Integer> firstSixItems = data.stream().limit(6).collect(toImmutableList());

        subscriber.request(5)
                  .assertValues(firstSixItems.toArray(EMPTY_INTEGERS));

        assertThat(intercepted)
                .as("onEmit callback should have been executed exactly 6 times")
                .isEqualTo(firstSixItems);

        subscriber.requestMax()
                  .assertValues(data.toArray(EMPTY_INTEGERS));

        assertThat(intercepted)
                .as("onEmit callback should have been executed exactly 10 times")
                .isEqualTo(data);
    }

    @Test
    void testCancelledEmitterReleaseSubscriberReference() throws InterruptedException {
        assertThat(checkReleasedSubscriber((e, s) -> s.cancel()))
                .as("Subscriber reference should be released after cancel!")
                .isTrue();

        assertThat(checkReleasedSubscriber((e, s) -> {
            s.cancel();
            e.complete();
        })).as("Subscriber reference should be released after cancel followed by complete!")
           .isTrue();

        assertThat(checkReleasedSubscriber((e, s) -> {
            e.complete();
            s.cancel();
        })).as("Subscriber reference should be released after complete followed by cancel!")
           .isTrue();

        assertThat(checkReleasedSubscriber((e, s) -> {
            e.fail(new RuntimeException("BOOM!"));
            s.cancel();
        })).as("Subscriber reference should be released after fail followed by cancel!")
           .isTrue();

        assertThat(checkReleasedSubscriber((e, s) -> {
            s.cancel();
            e.fail(new RuntimeException("BOOM!"));
        })).as("Subscriber reference should be released after complete followed by fail!")
           .isTrue();
    }

    private static boolean checkReleasedSubscriber(
            BiConsumer<BufferedEmittingPublisher<Integer>, TestSubscriber<Integer>> biConsumer)
            throws InterruptedException {
        final BufferedEmittingPublisher<Integer> emitter = new BufferedEmittingPublisher<>();
        TestSubscriber<Integer> subscriber = new TestSubscriber<>();
        final ReferenceQueue<TestSubscriber<Integer>> queue = new ReferenceQueue<>();
        final WeakReference<TestSubscriber<Integer>> ref = new WeakReference<>(subscriber, queue);
        emitter.subscribe(subscriber);
        biConsumer.accept(emitter, subscriber);
        subscriber = null;
        System.gc();

        return ref.equals(queue.remove(100));
    }

    @Test
    void testBackPressureWithLazyComplete() {
        final BufferedEmittingPublisher<Integer> emitter = new BufferedEmittingPublisher<>();

        final List<Integer> data = IntStream.range(0, 10)
                                            .boxed()
                                            .collect(toImmutableList());

        data.forEach(emitter::emit);

        final TestSubscriber<Integer> subscriber = new TestSubscriber<>();
        emitter.subscribe(subscriber);

        subscriber.assertEmpty()
                  .request(1)
                  .assertItemCount(1)
                  .request(2)
                  .assertItemCount(3)
                  .assertNotTerminated();

        assertThat(emitter.emit(10)).isEqualTo(8);

        subscriber.request(3)
                  .assertItemCount(6);

        assertThat(emitter.emit(11)).isEqualTo(6);

        subscriber.requestMax()
                  .assertNotTerminated();

        emitter.complete();
        subscriber.assertValues(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
                  .assertComplete();
    }
}
