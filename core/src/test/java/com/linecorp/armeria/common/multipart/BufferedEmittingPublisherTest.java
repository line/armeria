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
 * Copyright (c) 2017, 2020 Oracle and/or its affiliates.
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
 */

package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.CommonPools;

/**
 * The BufferedEmittingPublisherTest.
 */
class BufferedEmittingPublisherTest {

    // Forked from https://github.com/oracle/helidon/blob/1701c0837086f754f4a44f2f33e1cc0e2862352b/common/reactive/src/test/java/io/helidon/common/reactive/BufferedEmittingPublisherTest.java

    private static final double OTHER_THREAD_EXECUTION_RATIO = 0.8;
    private static final int BOUND = 5;
    private static final int ITERATION_COUNT = 1000;

    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicLong check = new AtomicLong(0);

    @Test
    void sanityPublisherCheck() throws Exception {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        publisher.subscribe(new Subscriber<Long>() {
            @Nullable
            Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(Long value) {
                if (!check.compareAndSet(value - 1, value)) {
                    throw new IllegalStateException(
                            "found: " + check.get() + " (expected: " + (value - 1) + ')');
                }
                if (ThreadLocalRandom.current().nextDouble(0, 1) < OTHER_THREAD_EXECUTION_RATIO) {
                    CommonPools.workerGroup().submit(() -> subscription
                            .request(ThreadLocalRandom.current().nextLong(1, BOUND)));
                } else {
                    subscription.request(ThreadLocalRandom.current().nextLong(1, BOUND));
                }
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.submit(() -> {
            while (!Thread.currentThread().isInterrupted() && seq.get() < ITERATION_COUNT) {
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(0, 2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted", e);
                }
                publisher.emit(seq.incrementAndGet());
            }
            finishedLatch.countDown();
        });

        try {
            if (!finishedLatch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Didn't finish in timely manner");
            }
        } finally {
            executorService.shutdown();
        }
    }

    @Test
    void testDoubleSubscribe() {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final TestSubscriber<Long> subscriber1 = new TestSubscriber<>();
        final TestSubscriber<Long> subscriber2 = new TestSubscriber<>();
        publisher.subscribe(subscriber1);
        publisher.subscribe(subscriber2);
        assertThat(subscriber1.isComplete()).isFalse();
        assertThat(subscriber2.getLastError()).isNotNull();
        assertThat(subscriber2.getLastError()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testNegativeSubscription() {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final TestSubscriber<Long> subscriber = new TestSubscriber<Long>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(-1);
            }
        };
        publisher.subscribe(subscriber);
        assertThat(subscriber.isComplete()).isFalse();
        assertThat(subscriber.getLastError()).isNotNull();
        assertThat(subscriber.getLastError()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testError() {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final TestSubscriber<Long> subscriber = new TestSubscriber<>();
        publisher.subscribe(subscriber);
        publisher.fail(new IllegalStateException("foo!"));
        assertThat(subscriber.isComplete()).isFalse();
        assertThat(subscriber.getLastError()).isNotNull();
        assertThat(subscriber.getLastError()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testErrorBeforeSubscribe() {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final TestSubscriber<Long> subscriber = new TestSubscriber<>();
        publisher.fail(new IllegalStateException("foo!"));
        publisher.subscribe(subscriber);
        subscriber.request1();
        assertThat(subscriber.isComplete()).isFalse();
        assertThat(subscriber.getLastError()).isNotNull();
        assertThat(subscriber.getLastError()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testErrorBadOnError() {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final TestSubscriber<Long> subscriber = new TestSubscriber<Long>() {
            @Override
            public void onError(Throwable throwable) {
                throw new UnsupportedOperationException("foo!");
            }
        };
        publisher.subscribe(subscriber);
        try {
            publisher.fail(new IllegalStateException("foo!"));
            throw new AssertionError("an exception should have been thrown");
        } catch (IllegalStateException ex) {
            assertThat(ex.getCause()).isNotNull();
            assertThat(ex.getCause()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void testComplete() {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final TestSubscriber<Long> subscriber = new TestSubscriber<>();
        publisher.subscribe(subscriber);
        publisher.complete();
        assertThat(subscriber.isComplete()).isTrue();
        assertThat(publisher.isCompleted()).isTrue();
        assertThat(subscriber.getLastError()).isNull();
    }

    @Test
    void testSubmitBadOnNext() {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final TestSubscriber<Long> subscriber = new TestSubscriber<Long>() {
            @Override
            public void onNext(Long item) {
                throw new UnsupportedOperationException("foo!");
            }
        };
        publisher.subscribe(subscriber);
        subscriber.request1();
        publisher.emit(15L);
        assertThat(subscriber.isComplete()).isFalse();
        assertThat(subscriber.getLastError()).isNotNull();
        assertThat(subscriber.getLastError()).isInstanceOf(IllegalStateException.class);
        assertThat(subscriber.getLastError().getCause()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testRequiresMoreItems() {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final TestSubscriber<Long> subscriber = new TestSubscriber<>();
        publisher.subscribe(subscriber);
        subscriber.request1();
        assertThat(subscriber.isComplete()).isFalse();
        assertThat(publisher.hasRequests()).isTrue();
    }

    @Test
    void testHookOnRequested() {
        final AtomicLong requested = new AtomicLong();
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        publisher.onRequest((n, demand) -> requested.set(n));
        final TestSubscriber<Long> subscriber = new TestSubscriber<Long>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }
        };
        publisher.subscribe(subscriber);
        assertThat(requested.get()).isEqualTo(1);
    }

    @Test
    void testHookOnCancel() {
        final BufferedEmittingPublisher<Long> publisher = new BufferedEmittingPublisher<>();
        final TestSubscriber<Long> subscriber = new TestSubscriber<Long>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.cancel();
            }
        };
        publisher.subscribe(subscriber);
        assertThatThrownBy(() -> publisher.emit(0L))
                .isInstanceOf(IllegalStateException.class);
    }
}
