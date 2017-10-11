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
package com.linecorp.armeria.internal.logging;

import java.util.BitSet;
import java.util.Random;

/**
 * This sampler is appropriate for low-traffic instrumentation (ex servers that each receive <100K
 * requests), or those who do not provision random trace ids. It not appropriate for collectors as
 * the sampling decision isn't idempotent (consistent based on trace id).
 *
 * <h3>Implementation</h3>
 *
 * <p>This initializes a random bitset of size 100 (corresponding to 1% granularity). This means
 * that it is accurate in units of 100 traces. At runtime, this loops through the bitset, returning
 * the value according to a counter.
 *
 * <p>Forked from brave-core.
 */
final class CountingSampler extends Sampler {
    private final BitSet sampleDecisions;
    private int counter; // guarded by this

    /** Fills a bitset with decisions according to the supplied rate. */
    CountingSampler(float rate) {
        int outOf100 = (int) (rate * 100.0f);
        sampleDecisions = randomBitSet(100, outOf100, new Random());
    }

    /**
     * Reservoir sampling algorithm borrowed from Stack Overflow.
     * https://stackoverflow.com/questions/12817946/generate-a-random-bitset-with-n-1s
     */
    private static BitSet randomBitSet(int size, int cardinality, Random rnd) {
        BitSet result = new BitSet(size);
        int[] chosen = new int[cardinality];
        int i;
        for (i = 0; i < cardinality; ++i) {
            chosen[i] = i;
            result.set(i);
        }
        for (; i < size; ++i) {
            int j = rnd.nextInt(i + 1);
            if (j < cardinality) {
                result.clear(chosen[j]);
                result.set(i);
                chosen[j] = i;
            }
        }
        return result;
    }

    /**
     * Returns true if the trace ID should be measured.
     * Loops over the pre-canned decisions, resetting to zero when it gets to the end.
     */
    @Override
    public synchronized boolean isSampled() {
        boolean result = sampleDecisions.get(counter++);
        if (counter == 100) {
            counter = 0;
        }
        return result;
    }
}
