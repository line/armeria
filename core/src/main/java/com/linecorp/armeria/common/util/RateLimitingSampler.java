/*
 * Copyright 2019 LINE Corporation
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
 * Copyright 2013-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linecorp.armeria.common.util;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.annotations.VisibleForTesting;

/**
 * The rate-limited sampler allows you to choose an amount of traces to accept on a per-second
 * interval. The minimum number is 0 and the max is 2,147,483,647 (max int).
 *
 * <p>For example, to allow 10 traces per second, you'd initialize the following:
 * <pre>{@code
 * tracingBuilder.sampler(RateLimitingSampler.create(10));
 * }</pre>
 *
 * <h2>Appropriate Usage</h2>
 *
 * <p>If the rate is 10 or more traces per second, an attempt is made to distribute the accept
 * decisions equally across the second. For example, if the rate is 100, 10 will pass every
 * decisecond as opposed to bunching all pass decisions at the beginning of the second.
 *
 * <p>However, this sampler is insensitive to the trace ID and will operate correctly even if they are
 * not perfectly random.
 *
 * <h2>Implementation</h2>
 *
 * <p>The implementation uses {@link System#nanoTime} and tracks how many yes decisions occur
 * across a second window. When the rate is at least 10/s, the yes decisions are equally split over
 * 10 deciseconds, allowing a roll-over of unused yes decisions up until the end of the second.
 *
 * <p>Forked from brave-core 5.6.9 at b8c00c594cbf75a33788d3dc990f94b9c6f41c01
 */
final class RateLimitingSampler<T> implements Sampler<T> {

    static <T> Sampler<T> create(int samplesPerSecond) {
        checkArgument(samplesPerSecond >= 0,
                      "samplesPerSecond: %s (expected: >= 0)", samplesPerSecond);
        if (samplesPerSecond == 0) {
            return Sampler.never();
        }
        return new RateLimitingSampler<>(samplesPerSecond);
    }

    static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    static final long NANOS_PER_DECISECOND = NANOS_PER_SECOND / 10;

    @VisibleForTesting
    final MaxFunction maxFunction;
    private final AtomicInteger usage = new AtomicInteger();
    private final AtomicLong nextReset;

    RateLimitingSampler(int samplesPerSecond) {
        maxFunction =
                samplesPerSecond < 10 ? new LessThan10(samplesPerSecond) : new AtLeast10(samplesPerSecond);
        final long now = System.nanoTime();
        nextReset = new AtomicLong(now + NANOS_PER_SECOND);
    }

    @Override
    public boolean isSampled(Object ignored) {
        final long now = System.nanoTime();
        final long updateAt = nextReset.get();

        // First task is to determine if this request is later than the one second sampling window
        final long nanosUntilReset = -(now - updateAt); // because nanoTime can be negative
        if (nanosUntilReset <= 0) {
            // Attempt to move into the next sampling interval.
            // nanosUntilReset is now invalid regardless of race winner, so we can't sample based on it.
            if (nextReset.compareAndSet(updateAt, now + NANOS_PER_SECOND)) {
                usage.set(0);
            }

            // recurse as it is simpler than resetting all the locals.
            // reset happens once per second, this code doesn't take a second, so no infinite recursion.
            return isSampled(ignored);
        }

        // Now, we determine the amount of samples allowed for this interval, and sample accordingly
        final int max = maxFunction.max(nanosUntilReset);
        int prev;
        int next;
        do { // same form as java 8 AtomicLong.getAndUpdate
            prev = usage.get();
            next = prev + 1;
            if (next > max) {
                return false;
            }
        } while (!usage.compareAndSet(prev, next));
        return true;
    }

    @Override
    public String toString() {
        return "RateLimitingSampler()";
    }

    private abstract static class MaxFunction {
        abstract int max(long nanosUntilReset);
    }

    /**
     * For a reservoir of less than 10, we permit draining it completely at any time in the second.
     */
    @VisibleForTesting
    static final class LessThan10 extends MaxFunction {
        final int samplesPerSecond;

        LessThan10(int samplesPerSecond) {
            this.samplesPerSecond = samplesPerSecond;
        }

        @Override
        int max(long nanosUntilResetIgnored) {
            return samplesPerSecond;
        }
    }

    /**
     * For a reservoir of at least 10, we permit draining up to a decisecond watermark. Because the
     * rate could be odd, we may have a remainder, which is arbitrarily available. We allow any
     * remainders in the 1st decisecond or any time thereafter.
     *
     * <p>Ex. If the rate is 10/s then you can use 1 in the first decisecond, another 1 in the 2nd,
     * or up to 10 by the last.
     *
     * <p>Ex. If the rate is 103/s then you can use 13 in the first decisecond, another 10 in the
     * 2nd, or up to 103 by the last.
     */
    private static final class AtLeast10 extends MaxFunction {
        final int[] max;

        AtLeast10(int samplesPerSecond) {
            final int samplesPerDecisecond = samplesPerSecond / 10;
            final int remainder = samplesPerSecond % 10;
            max = new int[10];
            max[0] = samplesPerDecisecond + remainder;
            for (int i = 1; i < 10; i++) {
                max[i] = max[i - 1] + samplesPerDecisecond;
            }
        }

        @Override
        int max(long nanosUntilReset) {
            // Check to see if we are in the first or last interval
            if (nanosUntilReset > NANOS_PER_SECOND - NANOS_PER_DECISECOND) {
                return max[0];
            }
            if (nanosUntilReset < NANOS_PER_DECISECOND) {
                return max[9];
            }

            // Choose a slot based on the remaining deciseconds
            final int decisecondsUntilReset = (int) (nanosUntilReset / NANOS_PER_DECISECOND);
            return max[10 - decisecondsUntilReset];
        }
    }
}
