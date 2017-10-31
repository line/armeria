/*
 * Copyright 2017 LINE Corporation
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
 *
 *  Copyright 2016 Vladimir Bukhtoyarov
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.linecorp.armeria.common.metric;

import static com.linecorp.armeria.common.metric.RollingHdrQuantiles.MAX_VALUE;
import static com.linecorp.armeria.common.metric.RollingHdrQuantiles.MIN_VALUE;
import static com.linecorp.armeria.common.metric.RollingHdrQuantiles.NUM_SIGNIFICANT_VALUE_DIGITS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.HdrHistogram.DoubleHistogram;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;

import com.linecorp.armeria.common.util.Exceptions;

public class RollingHdrQuantilesTest {

    private static final Logger logger = LoggerFactory.getLogger(RollingHdrQuantilesTest.class);

    /**
     * Makes sure the pre-calculated {@link RollingHdrQuantiles#MAX_VALUE} is correct.
     */
    @Test
    public void valueRanges() {
        assertThat(MAX_VALUE).isEqualTo(findMaxValue(NUM_SIGNIFICANT_VALUE_DIGITS));
    }

    private static long findMaxValue(int numberOfSignificantValueDigits) {
        long min = 0;
        long max = Long.MAX_VALUE;
        for (;;) {
            final long mid = (max - min) / 2 + min;
            if (min == mid) {
                logger.debug("{}: {}", numberOfSignificantValueDigits, min);
                return min;
            }

            if (canRecord(numberOfSignificantValueDigits, mid)) {
                min = mid;
            } else {
                max = mid - 1;
            }
        }
    }

    private static boolean canRecord(int numberOfSignificantValueDigits, long value) {
        final DoubleHistogram histogram = new DoubleHistogram(numberOfSignificantValueDigits);
        try {
            histogram.recordValue(MIN_VALUE);
            histogram.recordValue(value);

            // Make sure it is possible to create a copy of the histogram.
            new DoubleHistogram(histogram);
            return true;
        } catch (Exception e) {
            final Throwable cause = Throwables.getRootCause(e);
            if (cause instanceof Error) {
                throw (Error) cause;
            }

            // Recorder rejected the value that exceeds the dynamic range limits.
            return false;
        }
    }

    @Test
    public void test() {
        final AtomicLong time = new AtomicLong();
        final RollingHdrQuantiles q = new RollingHdrQuantiles(3, 1000, time::get);

        q.observe(10);
        q.observe(20);
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isBetween(9.0, 11.0);
        assertThat(q.get(1.0)).isBetween(19.0, 21.0);

        time.getAndAdd(900); // 900
        q.observe(30);
        q.observe(40);
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isBetween(9.0, 11.0);
        assertThat(q.get(1.0)).isBetween(39.0, 41.0);

        time.getAndAdd(99); // 999
        q.observe(9);
        q.observe(60);
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isBetween(8.0, 10.0);
        assertThat(q.get(1.0)).isBetween(59.0, 61.0);

        time.getAndAdd(1); // 1000
        q.observe(12);
        q.observe(70);
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isBetween(8.0, 10.0);
        assertThat(q.get(1.0)).isBetween(69.0, 71.0);

        time.getAndAdd(1001); // 2001
        q.observe(13);
        q.observe(80);
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isBetween(8.0, 10.0);
        assertThat(q.get(1.0)).isBetween(79.0, 81.0);

        time.getAndAdd(1000); // 3001
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isBetween(8.0, 10.0);
        assertThat(q.get(1.0)).isBetween(79.0, 81.0);

        time.getAndAdd(999); // 4000
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isBetween(11.0, 13.0);
        assertThat(q.get(1.0)).isBetween(79.0, 81.0);
        q.observe(1);
        q.observe(200);
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isBetween(0.0, 2.0);
        assertThat(q.get(1.0)).isBetween(199.0, 201.0);

        time.getAndAdd(10000); // 14000
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isZero();
        assertThat(q.get(1.0)).isZero();
        q.observe(3);

        time.addAndGet(3999); // 17999
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isBetween(2.0, 4.0);
        assertThat(q.get(1.0)).isBetween(2.0, 4.0);

        time.addAndGet(1); // 18000
        q.discardCachedSnapshot();
        assertThat(q.get(0.0)).isZero();
        assertThat(q.get(1.0)).isZero();

        // Memory footprint should be between 94 and 98 KiB.
        assertThat(q.estimatedFootprintInBytes()).isBetween(94 * 1024, 98 * 1024);
    }

    /**
     * Makes sure the value less than {@link RollingHdrQuantiles#MIN_VALUE} is clamped.
     */
    @Test
    public void testMinValue() {
        final RollingHdrQuantiles q = new RollingHdrQuantiles();
        q.observe(MIN_VALUE / 2);
        assertThat(q.get(0)).isBetween(MIN_VALUE * 0.99, MIN_VALUE * 1.01);
    }

    /**
     * Makes sure the value greater than {@link RollingHdrQuantiles#MAX_VALUE} is clamped.
     */
    @Test
    public void testMaxValue() {
        final RollingHdrQuantiles q = new RollingHdrQuantiles();
        q.observe(MAX_VALUE * 2);
        assertThat(q.get(0)).isBetween(MAX_VALUE * 0.99, MAX_VALUE * 1.01);
    }

    @Test
    public void testToString() {
        assertThat(new RollingHdrQuantiles().toString()).isNotEmpty();
    }

    @Test(timeout = 10000)
    public void testThatConcurrentThreadsNotHungWithThreeChunks() throws Exception {
        final RollingHdrQuantiles q = new RollingHdrQuantiles(3, 1000);
        runInParallel(q, Duration.ofSeconds(3).toMillis());
    }

    private static void runInParallel(RollingHdrQuantiles reservoir, long durationMillis) throws Exception {
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final Thread[] threads = new Thread[Runtime.getRuntime().availableProcessors() * 2];
        final long start = System.currentTimeMillis();
        final CountDownLatch latch = new CountDownLatch(threads.length);
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    // update reservoir 100 times and take snapshot on each cycle
                    while (errorRef.get() == null && System.currentTimeMillis() - start < durationMillis) {
                        for (int j = 1; j <= 10; j++) {
                            reservoir.observe(ThreadLocalRandom.current().nextDouble(j));
                        }
                        reservoir.discardCachedSnapshot();
                        reservoir.get(0);
                    }
                } catch (Exception e) {
                    errorRef.set(e);
                } finally {
                    latch.countDown();
                }
            });
            threads[i].start();
        }
        latch.await();
        if (errorRef.get() != null) {
            Exceptions.throwUnsafely(errorRef.get());
        }
    }
}
