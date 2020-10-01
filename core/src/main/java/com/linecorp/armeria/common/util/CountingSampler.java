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
 *
 * Copyright 2013 <kristofa@github.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.util;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;

/**
 * This sampler is appropriate for low-traffic instrumentation (ex servers that each receive <100K
 * requests), or those who do not provision random trace ids. It is not appropriate for collectors
 * as the sampling decision isn't idempotent (consistent based on trace id).
 *
 * <h2>Implementation</h2>
 *
 * <p>This initializes a random bitset of size 100 (corresponding to 1% granularity). This means
 * that it is accurate in units of 100 traces. At runtime, this loops through the bitset, returning
 * the value according to a counter.
 *
 * <p>Forked from brave-core 5.6.3 at d4cbd86e1df75687339da6ec2964d42ab3a8cf14
 */
final class CountingSampler<T> implements Sampler<T> {

    /**
     * Creates a new instance.
     *
     * @param probability {@code 0.0} means never sample, {@code 1.0} means always sample.
     *                    Otherwise minimum probability is between {@code 0.01} and {@code 1.0}.
     */
    static <T> Sampler<T> create(float probability) {
        final int percent = (int) (probability * 100.0f);
        checkArgument(percent >= 0 && percent <= 100,
                      "probability: %s (expected: 0.0 <= probability <= 1.0)", probability);
        if (percent == 0) {
            return Sampler.never();
        }
        if (percent == 100) {
            return Sampler.always();
        }
        return new CountingSampler<>(percent);
    }

    private final AtomicInteger counter;
    @VisibleForTesting
    final BitSet sampleDecisions;

    /** Fills a bitset with decisions according to the supplied percent. */
    CountingSampler(int percent) {
        this(percent, new Random());
    }

    /**
     * Fills a bitset with decisions according to the supplied percent with the supplied {@link Random}.
     */
    CountingSampler(int percent, Random random) {
        counter = new AtomicInteger();
        sampleDecisions = randomBitSet(100, percent, random);
    }

    /** loops over the pre-canned decisions, resetting to zero when it gets to the end. */
    @Override
    public boolean isSampled(Object ignored) {
        return sampleDecisions.get(mod(counter.getAndIncrement(), 100));
    }

    @Override
    public String toString() {
        return "CountingSampler()";
    }

    /**
     * Returns a non-negative mod.
     */
    static int mod(int dividend, int divisor) {
        final int result = dividend % divisor;
        return result >= 0 ? result : divisor + result;
    }

    /**
     * Reservoir sampling algorithm borrowed from <a href=
     * "http://stackoverflow.com/questions/12817946/generate-a-random-bitset-with-n-1s">Stack Overflow</a>.
     */
    static BitSet randomBitSet(int size, int cardinality, Random rnd) {
        final BitSet result = new BitSet(size);
        final int[] chosen = new int[cardinality];
        int i;
        for (i = 0; i < cardinality; ++i) {
            chosen[i] = i;
            result.set(i);
        }
        for (; i < size; ++i) {
            final int j = rnd.nextInt(i + 1);
            if (j < cardinality) {
                result.clear(chosen[j]);
                result.set(i);
                chosen[j] = i;
            }
        }
        return result;
    }
}
