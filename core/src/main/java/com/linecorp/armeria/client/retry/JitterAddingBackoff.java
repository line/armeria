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

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

final class JitterAddingBackoff extends BackoffWrapper {
    private final LongSupplier jitter;

    JitterAddingBackoff(Backoff delegate, long minIntervalMillis, long maxIntervalMillis) {
        super(delegate);
        jitter = () -> ThreadLocalRandom.current().nextLong(minIntervalMillis, maxIntervalMillis);
    }

    @Override
    public long nextIntervalMillis(int numAttemptsSoFar) {
        final long nextIntervalMillis = delegate().nextIntervalMillis(numAttemptsSoFar);
        if (nextIntervalMillis < 0) {
            return nextIntervalMillis;
        }
        return Math.max(0, nextIntervalMillis + jitter.getAsLong());
    }
}
