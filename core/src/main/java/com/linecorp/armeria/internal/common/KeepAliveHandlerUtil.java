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

import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;

public final class KeepAliveHandlerUtil {

    private static final long MIN_MAX_CONNECTION_AGE_NANOS = TimeUnit.SECONDS.toNanos(1);

    public static boolean needsKeepAliveHandler(long idleTimeoutMillis, long pingIntervalMillis,
                                                long maxConnectionAgeMillis, int maxNumRequestsPerConnection) {
        return idleTimeoutMillis > 0 || pingIntervalMillis > 0 ||
               maxConnectionAgeMillis > 0 || maxNumRequestsPerConnection > 0;
    }

    @VisibleForTesting
    static long jitteredMaxConnectionAgeNanos(long maxConnectionAgeNanos,
                                              double jitterRate, double randomValue) {
        if (maxConnectionAgeNanos <= 0 || jitterRate == 0) {
            return maxConnectionAgeNanos;
        }

        final long jitteredLowerBound = (long) (maxConnectionAgeNanos * (1 - jitterRate));
        final long lowerBound = Math.min(maxConnectionAgeNanos,
                                         Math.max(MIN_MAX_CONNECTION_AGE_NANOS, jitteredLowerBound));
        return lowerBound + (long) ((maxConnectionAgeNanos - lowerBound) * randomValue);
    }

    private KeepAliveHandlerUtil() {}
}
