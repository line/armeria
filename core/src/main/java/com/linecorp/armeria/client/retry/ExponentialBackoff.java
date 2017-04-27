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

import com.google.common.base.MoreObjects;

final class ExponentialBackoff extends AbstractBackoff {
    private long currentIntervalMillis;
    private final long maxIntervalMillis;
    private final double multiplier;

    ExponentialBackoff(long minIntervalMillis, long maxIntervalMillis, double multiplier) {
        checkArgument(multiplier > 1.0, "multiplier: %s (expected: > 1.0)", multiplier);
        checkArgument(minIntervalMillis >= 0, "minIntervalMillis: %s (expected: >= 0)", minIntervalMillis);
        checkArgument(minIntervalMillis <= maxIntervalMillis, "maxIntervalMillis: %s (expected: >= %s)",
                      maxIntervalMillis, minIntervalMillis);
        currentIntervalMillis = minIntervalMillis;
        this.maxIntervalMillis = maxIntervalMillis;
        this.multiplier = multiplier;
    }

    @Override
    protected long doNextIntervalMillis(int numAttemptsSoFar) {
        if (currentIntervalMillis >= maxIntervalMillis) {
            return maxIntervalMillis;
        }
        long nextInterval = currentIntervalMillis;
        currentIntervalMillis = saturatedMultiply(currentIntervalMillis, multiplier);
        return nextInterval;
    }

    private static long saturatedMultiply(long left, double right) {
        double result = left * right;
        return result >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("currentIntervalMillis", currentIntervalMillis)
                          .add("maxIntervalMillis", maxIntervalMillis)
                          .add("multiplier", multiplier)
                          .toString();
    }
}
