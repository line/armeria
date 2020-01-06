/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.util.Ticker;

class SlidingWindowCounterTest {

    private static final AtomicLong ticker = new AtomicLong();

    @Test
    void testInitialState() {
        final SlidingWindowCounter counter = new SlidingWindowCounter(ticker::get, Duration.ofSeconds(10),
                                                                      Duration.ofSeconds(1));

        assertThat(counter.count()).isEqualTo(EventCount.of(0, 0));
    }

    @Test
    void testOnSuccess() {
        final SlidingWindowCounter counter = new SlidingWindowCounter(ticker::get, Duration.ofSeconds(10),
                                                                      Duration.ofSeconds(1));

        assertThat(counter.onSuccess()).isNull();

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertThat(counter.onFailure()).isEqualTo(EventCount.of(1, 0));
        assertThat(counter.count()).isEqualTo(EventCount.of(1, 0));
    }

    @Test
    void testOnFailure() {
        final SlidingWindowCounter counter = new SlidingWindowCounter(ticker::get, Duration.ofSeconds(10),
                                                                      Duration.ofSeconds(1));

        assertThat(counter.onFailure()).isNull();

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertThat(counter.onFailure()).isEqualTo(EventCount.of(0, 1));
        assertThat(counter.count()).isEqualTo(EventCount.of(0, 1));
    }

    @Test
    void testTrim() {
        final SlidingWindowCounter counter = new SlidingWindowCounter(ticker::get, Duration.ofSeconds(10),
                                                                      Duration.ofSeconds(1));

        assertThat(counter.onSuccess()).isNull();
        assertThat(counter.onFailure()).isNull();

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertThat(counter.onFailure()).isEqualTo(EventCount.of(1, 1));
        assertThat(counter.count()).isEqualTo(EventCount.of(1, 1));

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(11));
        assertThat(counter.onFailure()).isEqualTo(EventCount.of(0, 0));
        assertThat(counter.count()).isEqualTo(EventCount.of(0, 0));
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        final SlidingWindowCounter counter = new SlidingWindowCounter(
                Ticker.systemTicker(), Duration.ofMinutes(5), Duration.ofMillis(1));

        final int worker = 6;
        final int batch = 100000;

        final AtomicLong success = new AtomicLong();
        final AtomicLong failure = new AtomicLong();

        final CyclicBarrier barrier = new CyclicBarrier(worker);

        final List<Thread> threads = new ArrayList<>(worker);

        for (int i = 0; i < worker; i++) {
            final Thread t = new Thread(() -> {
                try {
                    barrier.await();

                    long s = 0;
                    long f = 0;
                    for (int j = 0; j < batch; j++) {
                        final double r = ThreadLocalRandom.current().nextDouble();
                        if (r > 0.6) {
                            counter.onSuccess();
                            s++;
                        } else if (r > 0.2) {
                            counter.onFailure();
                            f++;
                        }
                    }
                    success.addAndGet(s);
                    failure.addAndGet(f);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            threads.add(t);
            t.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        await().untilAsserted(() -> assertThat(counter.onFailure()).isNotNull());
        assertThat(counter.count()).isEqualTo(EventCount.of(success.get(), failure.get()));
    }

    @Test
    void testLateBucket() {
        final SlidingWindowCounter counter = new SlidingWindowCounter(ticker::get, Duration.ofSeconds(10),
                                                                      Duration.ofSeconds(1));

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(-1));
        assertThat(counter.onSuccess()).isNull();
        assertThat(counter.count()).isEqualTo(EventCount.of(0, 0));
    }
}
