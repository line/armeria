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
package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.math.LongMath.saturatedAdd;

import java.util.ArrayList;
import java.util.List;

import com.google.common.base.MoreObjects;

final class FibonacciBackoff extends AbstractBackoff {
    private final long initialDelayMillis;
    private final long maxDelayMillis;

    private final long[] precomputedDelays;

    FibonacciBackoff(long initialDelayMillis, long maxDelayMillis) {
        checkArgument(initialDelayMillis >= 0,
                      "initialDelayMillis: %s (expected: >= 0)", initialDelayMillis);
        checkArgument(initialDelayMillis <= maxDelayMillis, "maxDelayMillis: %s (expected: >= %s)",
                      maxDelayMillis, initialDelayMillis);
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;

        final List<Long> precomputed = new ArrayList<>();
        precomputed.add(initialDelayMillis);
        precomputed.add(initialDelayMillis);

        for (int i = 2; i <= 30; i++) {
            final long delay = saturatedAdd(precomputed.get(i - 2), precomputed.get(i - 1));
            if (delay < maxDelayMillis) {
                precomputed.add(delay);
            } else {
                precomputed.add(maxDelayMillis);
                break;
            }
        }

        precomputedDelays = precomputed.stream().mapToLong(l -> l).toArray();
    }

    @Override
    protected long doNextDelayMillis(int numAttemptsSoFar) {
        final long nextDelay = fibDelay(numAttemptsSoFar);
        return Math.min(nextDelay, maxDelayMillis);
    }

    private long fibDelay(int n) {
        final int length = precomputedDelays.length;
        //we already know the first not-repeating delays, so we can look them up
        if (n < length) {
            return precomputedDelays[n - 1];
        //we know that we have reached the maxDelay already, no additional calculations required
        } else if (precomputedDelays[length - 1] == maxDelayMillis) {
            return maxDelayMillis;
        //we have to generate new delays
        } else {
            long a = precomputedDelays[length - 2];
            long b = precomputedDelays[length - 1];
            long c;
            for (int i = 0; i <= n - length; i++) {
                c = a;
                a = b;
                b += c;
            }
            return a;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("initialDelayMillis", initialDelayMillis)
                          .add("maxDelayMillis", maxDelayMillis).toString();
    }
}
