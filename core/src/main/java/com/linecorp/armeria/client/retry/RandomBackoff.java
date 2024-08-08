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
package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.google.common.base.MoreObjects;

final class RandomBackoff extends AbstractBackoff {
    private final LongSupplier nextDelay;
    private final long minDelayMillis;
    private final long maxDelayMillis;

    RandomBackoff(long minDelayMillis, long maxDelayMillis, Supplier<Random> randomSupplier) {
        checkArgument(minDelayMillis >= 0, "minDelayMillis: %s (expected: >= 0)", minDelayMillis);
        checkArgument(minDelayMillis <= maxDelayMillis, "maxDelayMillis: %s (expected: >= %s)",
                      maxDelayMillis, minDelayMillis);
        requireNonNull(randomSupplier, "randomSupplier");

        this.minDelayMillis = minDelayMillis;
        this.maxDelayMillis = maxDelayMillis;

        final long bound = maxDelayMillis - minDelayMillis + 1;
        if (minDelayMillis == maxDelayMillis) {
            nextDelay = () -> minDelayMillis;
        } else {
            nextDelay = () -> nextLong(randomSupplier.get(), bound) + minDelayMillis;
        }
    }

    @Override
    protected long doNextDelayMillis(int numAttemptsSoFar) {
        return nextDelay.getAsLong();
    }

    static long nextLong(Random random, long bound) {
        assert bound > 0;
        final long mask = bound - 1;
        long result = random.nextLong();

        if ((bound & mask) == 0L) {
            // power of two
            result &= mask;
        } else { // reject over-represented candidates
            for (long u = result >>> 1; u + mask - (result = u % bound) < 0L; u = random.nextLong() >>> 1) {
                continue;
            }
        }

        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("minDelayMillis", minDelayMillis)
                          .add("maxDelayMillis", maxDelayMillis)
                          .toString();
    }
}
