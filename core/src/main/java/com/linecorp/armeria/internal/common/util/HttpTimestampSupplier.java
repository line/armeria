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

package com.linecorp.armeria.internal.common.util;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;

/**
 * Utility to supply HTTP formatted timestamps in an optimized way. Nanosecond level precision around the
 * border of a second has minor approximations, but it is still fine to use these for HTTP timestamps as
 * such precision is not expected.
 */
public final class HttpTimestampSupplier {

    /**
     * Returns the HTTP formatted timestamp for the current time.
     */
    public static String currentTime() {
        return INSTANCE.currentTimestamp();
    }

    private static final HttpTimestampSupplier INSTANCE = new HttpTimestampSupplier();

    private static final long NANOS_IN_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final Supplier<Instant> instantSupplier;
    private final LongSupplier nanoTimeSupplier;

    // We do not use volatile fields because time only goes up - stale reads of nextUpdateNanos will
    // cause extra computation of timestamp but will not affect the accuracy. As this is intended to
    // be called from event loop threads, in practice there is an upper limit of 2 * num CPUs extra
    // computations of timestamp per second whereas in performance-constrained situations there will
    // be far more reads per second.
    private String timestamp = "";
    private long nextUpdateNanos;

    private HttpTimestampSupplier() {
        this(Instant::now, System::nanoTime);
    }

    @VisibleForTesting
    HttpTimestampSupplier(Supplier<Instant> instantSupplier, LongSupplier nanoTimeSupplier) {
        this.instantSupplier = requireNonNull(instantSupplier, "instantSupplier");
        this.nanoTimeSupplier = requireNonNull(nanoTimeSupplier, "nanoTimeSupplier");
        nextUpdateNanos = nanoTimeSupplier.getAsLong();
    }

    @VisibleForTesting
    String currentTimestamp() {
        final long currentTimeNanos = nanoTimeSupplier.getAsLong();

        if (nextUpdateNanos - currentTimeNanos > 0) {
            return timestamp;
        }

        final Instant now = instantSupplier.get();

        // The next time we need to update our formatted timestamp is the next time System.nanoTime()
        // equals the following second. We can determine this by adding one second to our current
        // nanoTime minus the nanos part of the current system time (i.e., the number of nanos since the
        // last second).
        nextUpdateNanos = currentTimeNanos - now.getNano() + NANOS_IN_SECOND;

        return timestamp = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}
