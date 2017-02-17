/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

final class RandomBackoff implements Backoff {
    private final LongSupplier nextInterval;
    private final long minIntervalMillis;
    private final long maxIntervalMillis;

    RandomBackoff(long minIntervalMillis, long maxIntervalMillis,
                  Supplier<Random> randomSupplier) {
        checkArgument(minIntervalMillis > 0, "minIntervalMillis: %s (expected: >= 0)", minIntervalMillis);
        checkArgument(minIntervalMillis <= maxIntervalMillis, "maxIntervalMillis: %s (expected: >= %s)",
                      maxIntervalMillis, minIntervalMillis);
        this.minIntervalMillis = minIntervalMillis;
        this.maxIntervalMillis = maxIntervalMillis;
        requireNonNull(randomSupplier, "randomSupplier");
        nextInterval = minIntervalMillis == maxIntervalMillis ?
                       () -> minIntervalMillis :
                       () -> randomSupplier.get()
                                           .longs(minIntervalMillis, maxIntervalMillis)
                                           .iterator()
                                           .nextLong();
    }

    @Override
    public long nextIntervalMillis(int numAttemptsSoFar) {
        return nextInterval.getAsLong();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("minIntervalMillis", minIntervalMillis)
                          .add("maxIntervalMillis", maxIntervalMillis)
                          .toString();
    }
}
