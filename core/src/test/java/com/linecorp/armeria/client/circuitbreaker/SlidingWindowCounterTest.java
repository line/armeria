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

import org.junit.Test;

import com.google.common.base.Ticker;
import com.google.common.testing.FakeTicker;

public class SlidingWindowCounterTest {

    private static final FakeTicker ticker = new FakeTicker();

    @Test
    public void testInitialState() {
        SlidingWindowCounter counter = new SlidingWindowCounter(ticker, Duration.ofSeconds(10),
                                                                Duration.ofSeconds(1));

        assertThat(counter.count()).isEqualTo(new EventCount(0, 0));
    }

    @Test
    public void testOnSuccess() {
        SlidingWindowCounter counter = new SlidingWindowCounter(ticker, Duration.ofSeconds(10),
                                                                Duration.ofSeconds(1));

        assertThat(counter.onSuccess()).isEmpty();

        ticker.advance(1, TimeUnit.SECONDS);
        assertThat(counter.onFailure()).contains(new EventCount(1, 0));
        assertThat(counter.count()).isEqualTo(new EventCount(1, 0));
    }

    @Test
    public void testOnFailure() {
        SlidingWindowCounter counter = new SlidingWindowCounter(ticker, Duration.ofSeconds(10),
                                                                Duration.ofSeconds(1));

        assertThat(counter.onFailure()).isEmpty();

        ticker.advance(1, TimeUnit.SECONDS);
        assertThat(counter.onFailure()).contains(new EventCount(0, 1));
        assertThat(counter.count()).isEqualTo(new EventCount(0, 1));
    }

    @Test
    public void testTrim() {
        SlidingWindowCounter counter = new SlidingWindowCounter(ticker, Duration.ofSeconds(10),
                                                                Duration.ofSeconds(1));

        assertThat(counter.onSuccess()).isEmpty();
        assertThat(counter.onFailure()).isEmpty();

        ticker.advance(1, TimeUnit.SECONDS);
        assertThat(counter.onFailure()).contains(new EventCount(1, 1));
        assertThat(counter.count()).isEqualTo(new EventCount(1, 1));

        ticker.advance(11, TimeUnit.SECONDS);
        assertThat(counter.onFailure()).contains(new EventCount(0, 0));
        assertThat(counter.count()).isEqualTo(new EventCount(0, 0));
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        SlidingWindowCounter counter = new SlidingWindowCounter(Ticker.systemTicker(), Duration.ofMinutes(5),
                                                                Duration.ofMillis(1));

        int worker = 6;
        int batch = 100000;

        AtomicLong success = new AtomicLong();
        AtomicLong failure = new AtomicLong();

        CyclicBarrier barrier = new CyclicBarrier(worker);

        List<Thread> threads = new ArrayList<>(worker);

        for (int i = 0; i < worker; i++) {
            Thread t = new Thread(() -> {
                try {
                    barrier.await();

                    long s = 0;
                    long f = 0;
                    for (int j = 0; j < batch; j++) {
                        double r = ThreadLocalRandom.current().nextDouble();
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

        await().untilAsserted(() -> assertThat(counter.onFailure()).isPresent());
        assertThat(counter.count()).isEqualTo(new EventCount(success.get(), failure.get()));
    }

    @Test
    public void testLateBucket() {
        SlidingWindowCounter counter = new SlidingWindowCounter(ticker, Duration.ofSeconds(10),
                                                                Duration.ofSeconds(1));

        ticker.advance(-1, TimeUnit.SECONDS);
        assertThat(counter.onSuccess()).isEmpty();
        assertThat(counter.count()).isEqualTo(new EventCount(0, 0));
    }
}
