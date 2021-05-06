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

package com.linecorp.armeria.server.throttling.bucket4j;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Refill;

/**
 * Stores configurations of a single Token-Bucket bandwidth limit.
 */
@UnstableApi
public final class BandwidthLimit {

    /**
     * Returns a newly created {@link BandwidthLimit}. Specifies limitation in
     * <a href="https://github.com/vladimir-bukhtoyarov/bucket4j/blob/1.3/doc-pages/token-bucket-brief-overview.md#token-bucket-algorithm">classic
     * interpretation</a> of token-bucket algorithm.
     *
     * @param limit          the bucket size - defines the count of tokens which can be held by the bucket
     *                       and defines the speed at which tokens are regenerated in the bucket
     * @param overdraftLimit defines the maximum overdraft count of tokens which can be held by
     *                       the bucket, this value must exceed the {@code limit}
     * @param initialSize    the initial number of tokens available to this bandwidth limit
     * @param period         the time window, during which the tokens will be regenerated
     */
    public static BandwidthLimit of(long limit, long overdraftLimit, long initialSize, Duration period) {
        return new BandwidthLimit(limit, overdraftLimit, initialSize, period);
    }

    /**
     * Returns a newly created {@link BandwidthLimit}. Specifies limitation in
     * <a href="https://github.com/vladimir-bukhtoyarov/bucket4j/blob/1.3/doc-pages/token-bucket-brief-overview.md#token-bucket-algorithm">classic
     * interpretation</a> of token-bucket algorithm.
     *
     * @param limit the bucket size - defines the count of tokens which can be held by the bucket
     *              and defines the speed at which tokens are regenerated in the bucket
     * @param overdraftLimit defines the maximum overdraft count of tokens which can be held by the bucket,
     *                       this value must exceed the {@code limit}
     * @param period the time window, during which the tokens will be regenerated
     */
    public static BandwidthLimit of(long limit, long overdraftLimit, Duration period) {
        return new BandwidthLimit(limit, overdraftLimit, 0L, period);
    }

    /**
     * Returns a newly created simple {@link BandwidthLimit}.
     * Specifies easy limitation of {@code limit} tokens per {@code period} time window.
     * @param limit the bucket size - defines the maximum count of tokens which can be held by the bucket
     *              and defines the speed at which tokens are regenerated in the bucket
     * @param period the time window, during which the tokens will be regenerated
     */
    public static BandwidthLimit of(long limit, Duration period) {
        return new BandwidthLimit(limit, 0L, 0L, period);
    }

    /**
     * Returns a newly created {@link BandwidthLimit}. Computes {@code limit}, {@code overdraftLimit},
     * {@code initialSize} and {@code period} out of a semicolon-separated {@code specification} string
     * that conforms to the following format,
     * as per <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">RateLimit Header
     * Fields for HTTP</a>:
     * <pre>{@code
     * <limit>;window=<period(in seconds)>[;burst=<overdraftLimit>][;initial=<initialSize>]
     * }</pre>
     * All {@code specification} string elements must come in the defined order.
     * For example:
     * <ul>
     *   <li>{@code 100;window=60;burst=1000} ({@code limit}=100, {@code overdraftLimit}=1000,
     *       {@code initialSize} and {@code period}=60seconds)</li>
     *   <li>{@code 100;window=60;burst=1000;initial=20} ({@code limit}=100, {@code overdraftLimit}=1000,
     *       {@code initialSize}=20 and {@code period}=60seconds)</li>
     *   <li>{@code 5000;window=1} ({@code limit}=5000 and {@code period}=1second)</li>
     * </ul>
     *
     * @param specification the specification used to create a {@link BandwidthLimit}
     */
    public static BandwidthLimit of(String specification) {
        return TokenBucketSpec.parseBandwidthLimit(specification);
    }

    private final long limit;
    private final long overdraftLimit;
    private final long initialSize;
    private final Duration period;

    BandwidthLimit(long limit, long overdraftLimit, long initialSize, Duration period) {
        // validate limit
        checkArgument(limit > 0L, "limit: %s (expected: > 0)", limit);
        this.limit = limit;

        // validate overdraftLimit
        checkArgument(overdraftLimit == 0L || overdraftLimit > limit,
                      "overdraftLimit: %s (expected: > %s)", overdraftLimit, limit);
        this.overdraftLimit = overdraftLimit;

        // validate initialSize
        checkArgument(initialSize >= 0L, "initialSize: %s (expected: >= 0)", initialSize);
        this.initialSize = initialSize;

        requireNonNull(period, "period");
        checkArgument(!period.isNegative() && !period.isZero(),
                      "period: %s (expected: > %s)", period, Duration.ZERO);
        this.period = period;
    }

    /**
     * Returns the bucket size, which defines the count of tokens which can be held by the bucket
     * and defines the speed at which tokens are regenerated in the bucket.
     * @return Bucket size.
     */
    public long limit() {
        return limit;
    }

    /**
     * Returns the maximum overdraft count of tokens which can be held by the bucket.
     * This value always exceeds the {@code BandwidthLimit#limit()} or equals to 0, if not specified.
     * @return Bucket maximum overdraft count.
     */
    public long overdraftLimit() {
        return overdraftLimit;
    }

    /**
     * Returns the number of initial available tokens available to this bandwidth limit. The initial limit
     * allows having lesser initial size, for example, in case of cold start in order to prevent denial of
     * service.
     * This value equals to 0, if not set.
     * @return the number of initial tokens in the bandwidth.
     */
    public long initialSize() {
        return initialSize;
    }

    /**
     * Returns the time window, during which the tokens will be regenerated for the given bandwidth limit.
     * @return Time window for the limit.
     */
    public Duration period() {
        return period;
    }

    /**
     * Constructs a new {@link Bandwidth}.
     */
    Bandwidth bandwidth() {
        final Bandwidth bandwidth;
        if (overdraftLimit > limit) {
            // overdraft has been defined
            bandwidth = Bandwidth.classic(overdraftLimit, Refill.greedy(limit, period));
            return (initialSize > 0L) ?
                   bandwidth.withInitialTokens(initialSize) : bandwidth.withInitialTokens(limit);
        } else {
            bandwidth = Bandwidth.simple(limit, period);
            return (initialSize > 0L) ? bandwidth.withInitialTokens(initialSize) : bandwidth;
        }
    }

    double ratePerSecond() {
        return (double) limit / period.getSeconds();
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("limit", limit)
                .add("overdraftLimit", overdraftLimit)
                .add("initialSize", initialSize)
                .add("period", period)
                .toString();
    }

    /**
     * Returns a string representation of the {@link BandwidthLimit} in the following format,
     * as per <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">RateLimit Header
     * Fields for HTTP</a>:
     * <pre>{@code
     * <limit>;window=<period(in seconds)>[;burst=<overdraftLimit>][;policy="token bucket"]
     * }</pre>
     * For example: "100;window=60;burst=1000".
     *
     * @return A {@link String} representation of the {@link BandwidthLimit}.
     * @see TokenBucketSpec#toString(BandwidthLimit)
     */
    String toSpecString() {
        return requireNonNull(TokenBucketSpec.toString(this));
    }
}
