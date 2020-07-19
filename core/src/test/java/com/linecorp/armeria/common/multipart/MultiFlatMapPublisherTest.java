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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

import com.google.common.util.concurrent.Uninterruptibles;

import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class MultiFlatMapPublisherTest {

    // Forked from https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/common/reactive/src/test/java/io/helidon/common/reactive/MultiFlatMapPublisherTest.java

    private static final int UPSTREAM_ITEM_COUNT = 100;
    private static final int ASYNC_MULTIPLY = 10;
    private static final int ASYNC_DELAY_MILLIS = 20;
    private static final int EXPECTED_EMISSION_COUNT = 1000;
    private static final List<Integer> TEST_DATA = IntStream.rangeClosed(1, UPSTREAM_ITEM_COUNT)
                                                            .boxed()
                                                            .collect(toImmutableList());

    Multi<Integer> items(int count) {
        return Multi.from(Flux.range(0, count));
    }

    void crossMap(int count) {
        final int inner = 1_000_000 / count;
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        items(count).flatMap(v -> items(inner)).subscribe(ts);

        ts.requestMax();

        assertThat(ts.getItems()).hasSize(1_000_000);
        assertThat(ts.isComplete()).isTrue();
        assertThat(ts.getLastError()).isNull();
    }

    void crossMapUnbounded(int count) {
        final int inner = 1_000_000 / count;
        final TestSubscriber<Integer> ts = new TestSubscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                super.onSubscribe(subscription);
                subscription.request(Long.MAX_VALUE);
            }
        };

        items(count).flatMap(v -> items(inner)).subscribe(ts);

        assertThat(ts.getItems()).hasSize(1_000_000);
        assertThat(ts.isComplete()).isTrue();
        assertThat(ts.getLastError()).isNull();
    }

    @Test
    void crossMap1() {
        crossMap(1);
    }

    @Test
    void crossMap10() {
        crossMap(10);
    }

    @Test
    void crossMap100() {
        crossMap(100);
    }

    @Test
    void crossMap1000() {
        crossMap(1000);
    }

    @Test
    void crossMap10000() {
        crossMap(10000);
    }

    @Test
    void crossMap100000() {
        crossMap(100000);
    }

    @Test
    void crossMap1000000() {
        crossMap(100000);
    }

    @Test
    void cancel() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        final EmitterProcessor<Integer> emitter1 = EmitterProcessor.create();
        final EmitterProcessor<Integer> emitter2 = EmitterProcessor.create();

        Multi.from(emitter1)
             .flatMap(v -> emitter2)
             .subscribe(ts);

        emitter1.onNext(1);

        ts.getSubscription().cancel();

        emitter1.isCancelled();
        emitter2.isCancelled();
    }

    @Test
    void empty() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();

        Multi.<Integer>empty()
                .flatMap(Multi::just)
                .subscribe(ts);

        assertThat(ts.getItems()).isEmpty();
        assertThat(ts.getLastError()).isNull();
        assertThat(ts.isComplete()).isTrue();
    }

    @Test
    void crossMapUnbounded1() {
        crossMapUnbounded(1);
    }

    @Test
    void crossMapUnbounded10() {
        crossMapUnbounded(10);
    }

    @Test
    void crossMapUnbounded100() {
        crossMapUnbounded(100);
    }

    @Test
    void crossMapUnbounded1000() {
        crossMapUnbounded(1000);
    }

    @Test
    void crossMapUnbounded10000() {
        crossMapUnbounded(10_000);
    }

    @Test
    void crossMapUnbounded100000() {
        crossMapUnbounded(100_000);
    }

    @Test
    void crossMapUnbounded1000000() {
        crossMapUnbounded(1_000_000);
    }

    @Test
    void justJust() {
        final TestSubscriber<Integer> ts = new TestSubscriber<>();
        Multi.singleton(1)
             .flatMap(Multi::just)
             .subscribe(ts);

        ts.request1();

        assertThat(ts.getItems()).containsExactly(1);
        assertThat(ts.getLastError()).isNull();
        assertThat(ts.isComplete()).isTrue();
    }

    @Test
    void justJustUnbounded() {
        final TestSubscriber<Integer> ts = new TestSubscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                super.onSubscribe(subscription);
                subscription.request(Long.MAX_VALUE);
            }
        };
        Multi.singleton(1)
             .flatMap(Multi::just)
             .subscribe(ts);

        assertThat(ts.getItems()).containsExactly(1);
        assertThat(ts.getLastError()).isNull();
        assertThat(ts.isComplete()).isTrue();
    }

    @Test
    void multi() {
        assertThat(Flux.from(Multi.from(TEST_DATA).flatMap(MultiFlatMapPublisherTest::asyncPublisher))
                       .distinct()
                       .collectList()
                       .block())
                .hasSize(EXPECTED_EMISSION_COUNT);
    }

    private static Publisher<String> asyncPublisher(Integer i) {
        final EmitterProcessor<String> pub = EmitterProcessor.create();
        new Thread(() -> {
            for (int o = 0; o < ASYNC_MULTIPLY; o++) {
                Uninterruptibles.sleepUninterruptibly(ASYNC_DELAY_MILLIS, TimeUnit.MILLISECONDS);
                pub.onNext(i + "#" + o);
            }
            pub.onComplete();
        }).start();
        return pub;
    }

    @Test
    void innerSourceOrderPreserved() {
        final ExecutorService executor1 = Executors.newSingleThreadExecutor();
        final ExecutorService executor2 = Executors.newSingleThreadExecutor();
        try {
            for (int p = 1; p < 256; p *= 2) {
                for (int i = 0; i < 1000; i++) {
                    final TestSubscriber<Integer> ts = new TestSubscriber<>(Long.MAX_VALUE);
                    Multi.just(
                            Flux.range(1, 100)
                                .subscribeOn(Schedulers.fromExecutorService(executor1)),
                            Flux.range(200, 100)
                                .subscribeOn(Schedulers.fromExecutorService(executor2)))
                         .flatMap(v -> v)
                         .subscribe(ts);

                    ts.awaitDone(5, TimeUnit.SECONDS)
                      .assertItemCount(200)
                      .assertComplete();

                    int last1 = 0;
                    int last2 = 199;
                    for (Integer v : ts.getItems()) {
                        if (v < 200) {
                            if (last1 + 1 != v) {
                                throw new IllegalStateException(
                                        "Out of order items: " + last1 + " -> " + v + " (p: " + p + ')');
                            }
                            last1 = v;
                        } else {
                            if (last2 + 1 != v) {
                                throw new IllegalStateException(
                                        "Out of order items: " + last2 + " -> " + v + " (p: " + p + ')');
                            }
                            last2 = v;
                        }
                    }
                }
            }
        } finally {
            executor1.shutdown();
            executor2.shutdown();
        }
    }
}
