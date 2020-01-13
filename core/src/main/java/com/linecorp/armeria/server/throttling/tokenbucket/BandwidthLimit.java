/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.throttling.tokenbucket;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import javax.annotation.Nonnull;

import com.google.common.base.MoreObjects;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Refill;

/**
 * Stores configurations of a Token-Bucket bandwidth limit.
 */
public class BandwidthLimit {

    private final long limit;
    private final long overdraftLimit;
    @Nonnull
    private final Duration period;

    /**
     * Specifies limitation in
     *   <a href="https://github.com/vladimir-bukhtoyarov/bucket4j/blob/1.3/doc-pages/token-bucket-brief-overview.md#token-bucket-algorithm">classic interpretation</a>
     *   of token-bucket algorithm.
     * @param limit the bucket size - defines the count of tokens which can be held by bucket
     *              and defines the speed at which tokens are regenerated in bucket
     * @param overdraftLimit defines the maximum overdraft count of tokens which can be held by bucket,
     *                       this must exceed the {@code limit}
     * @param period the time window, during which the tokens will be regenerated
     */
    BandwidthLimit(long limit, long overdraftLimit, @Nonnull Duration period) {
        // validate limit
        if (limit <= 0L) {
            throw new IllegalArgumentException("Bandwidth Limit must be positive");
        }
        this.limit = limit;

        // validate overdraftLimit
        if (overdraftLimit > 0L && overdraftLimit <= limit) {
            throw new IllegalArgumentException("Overdraft Limit has to exceed Bandwidth Limit");
        }
        this.overdraftLimit = overdraftLimit;

        requireNonNull(period, "period");
        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException(
                    "period: " + period + " (expected: > 0)");
        }
        this.period = period;
    }

    /**
     * Specifies easy limitation of {@code limit} tokens per {@code period} time window.
     * @param limit the bucket size - defines the maximum count of tokens which can be held by bucket
     *              and defines the speed at which tokens are regenerated in bucket
     * @param period the time window, during which the tokens will be regenerated
     */
    BandwidthLimit(long limit, @Nonnull Duration period) {
        this(limit, 0, period);
    }

    /**
     * The bucket size - defines the count of tokens which can be held by bucket
     * and defines the speed at which tokens are regenerated in bucket.
     * @return Bucket size.
     */
    public long limit() {
        return limit;
    }

    /**
     * The maximum overdraft count of tokens which can be held by bucket,
     * this must exceed the {@code BandwidthLimit#limit()}.
     * @return Bucket maximum overdraft count.
     */
    public long overdraftLimit() {
        return overdraftLimit;
    }

    /**
     * The time window, during which the tokens will be regenerated.
     * @return Time window for the limit.
     */
    @Nonnull
    public Duration period() {
        return period;
    }

    @Nonnull
    Bandwidth bandwidth() {
        if (overdraftLimit > limit) {
            // overdraft has been defined
            return Bandwidth.classic(overdraftLimit, Refill.greedy(limit, period));
        } else {
            return Bandwidth.simple(limit, period);
        }
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("limit", limit)
                .add("overdraftLimit", overdraftLimit)
                .add("period", period)
                .toString();
    }
}
