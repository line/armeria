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

import com.google.common.base.MoreObjects;

final class FibonacciBackoff extends AbstractBackoff {
    private final long initialDelayMillis;
    private final long maxDelayMillis;

    FibonacciBackoff(long initialDelayMillis, long maxDelayMillis) {
        checkArgument(initialDelayMillis >= 0,
                      "initialDelayMillis: %s (expected: >= 0)", initialDelayMillis);
        checkArgument(initialDelayMillis <= maxDelayMillis, "maxDelayMillis: %s (expected: >= %s)",
                      maxDelayMillis, initialDelayMillis);
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    @Override
    protected long doNextDelayMillis(int numAttemptsSoFar) {
        final long nextDelay = saturatedMultiply(initialDelayMillis, fib(numAttemptsSoFar));
        return Math.min(nextDelay, maxDelayMillis);
    }

    private static int fib(int n) {
        int a = 1;
        int b = 1;
        int c;
        for (int i = 0; i <= n - 2; i++) {
            c = a;
            a = b;
            b += c;
        }
        return a;
    }

    private static long saturatedMultiply(long left, int right) {
        return Long.MAX_VALUE / right < left ? Long.MAX_VALUE : left * right;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("initialDelayMillis", initialDelayMillis)
                          .add("maxDelayMillis", maxDelayMillis).toString();
    }
}
