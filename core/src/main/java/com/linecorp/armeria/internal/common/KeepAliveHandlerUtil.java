/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class KeepAliveHandlerUtil {

    private static final long MIN_JITTERED_MAX_CONNECTION_AGE_NANOS = TimeUnit.SECONDS.toNanos(1);

    public static boolean needsKeepAliveHandler(long idleTimeoutMillis, long pingIntervalMillis,
                                                long maxConnectionAgeMillis, int maxNumRequestsPerConnection) {
        return idleTimeoutMillis > 0 || pingIntervalMillis > 0 ||
               maxConnectionAgeMillis > 0 || maxNumRequestsPerConnection > 0;
    }

    public static double validateMaxConnectionAgeJitterRate(double maxConnectionAgeJitterRate) {
        checkArgument(maxConnectionAgeJitterRate >= 0.0 && maxConnectionAgeJitterRate <= 1.0,
                      "maxConnectionAgeJitterRate: %s (expected: >= 0.0 and <= 1.0)",
                      maxConnectionAgeJitterRate);
        return maxConnectionAgeJitterRate;
    }

    static long jitteredMaxConnectionAgeNanos(long maxConnectionAgeNanos, double jitterRate) {
        if (maxConnectionAgeNanos <= 0 || jitterRate == 0) {
            return maxConnectionAgeNanos;
        }

        final long jitteredLowerBound = (long) (maxConnectionAgeNanos * (1 - jitterRate));
        final long lowerBound = Math.max(MIN_JITTERED_MAX_CONNECTION_AGE_NANOS, jitteredLowerBound);
        if (lowerBound >= maxConnectionAgeNanos) {
            return maxConnectionAgeNanos;
        }
        return ThreadLocalRandom.current().nextLong(lowerBound, maxConnectionAgeNanos);
    }

    private KeepAliveHandlerUtil() {}
}
