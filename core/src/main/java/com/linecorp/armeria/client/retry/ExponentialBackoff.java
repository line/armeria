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

import static com.linecorp.armeria.client.retry.MathUtils.safeMultiply;

import com.google.common.base.MoreObjects;

final class ExponentialBackoff implements Backoff {
    private long currentIntervalMillis;
    private final long maxIntervalMillis;
    private final float multiplier;

    ExponentialBackoff(long minIntervalMillis, long maxIntervalMillis, float multiplier) {
        if (multiplier <= 1.0) {
            throw new IllegalArgumentException("multiplier: " + multiplier + " (expected: > 1.0)");
        }
        if (minIntervalMillis < 0) {
            throw new IllegalArgumentException("minIntervalMillis: " + minIntervalMillis + " (expected: >= 0)");
        }
        if (minIntervalMillis < maxIntervalMillis) {
            throw new IllegalArgumentException("maxIntervalMillis: " + maxIntervalMillis +
                                               " (expected: > minIntervalMillis: " + minIntervalMillis + ')');
        }
        currentIntervalMillis = minIntervalMillis;
        this.maxIntervalMillis = maxIntervalMillis;
        this.multiplier = multiplier;
    }

    @Override
    public long nextIntervalMillis(int numAttemptsSoFar) {
        long nextInterval = currentIntervalMillis;
        currentIntervalMillis = Math.min(safeMultiply(currentIntervalMillis, multiplier), maxIntervalMillis);
        return nextInterval;
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
