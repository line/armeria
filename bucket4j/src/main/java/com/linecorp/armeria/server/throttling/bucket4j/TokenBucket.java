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

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Stores configuration of the Token-Bucket algorithm, comprised of multiple limits.
 */
@UnstableApi
public final class TokenBucket {

    /**
     * Returns a newly created {@link TokenBucketBuilder}.
     */
    public static TokenBucketBuilder builder() {
        return new TokenBucketBuilder();
    }

    /**
     * Returns a newly created {@link TokenBucket}. Computes a set of {@link BandwidthLimit} out of
     * a comma-separated {@code specification} string that conforms to the following format,
     * as per <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">RateLimit Header
     * Fields for HTTP</a>:
     * <pre>{@code
     * <bandwidth limit 1>[, <bandwidth limit 2>[, etc.]]
     * }</pre>
     * The order of elements inside {@code specification} is not defined.
     * For example:
     * <ul>
     *   <li>{@code 100;window=60;burst=1000, 50000;window=3600}</li>
     * </ul>
     *
     * @param specification the specification used to create a {@link BandwidthLimit}
     * @see TokenBucketSpec#parseTokenBucket(String)
     */
    public static TokenBucket of(String specification) {
        return TokenBucketSpec.parseTokenBucket(specification);
    }

    /**
     * Returns a newly created {@link TokenBucket} with a single simple {@link BandwidthLimit}.
     * Specifies easy limitation of {@code limit} tokens per {@code period} time window.
     * @param limit the bucket size - defines the maximum count of tokens which can be held by the bucket
     *              and defines the speed at which tokens are regenerated in the bucket
     * @param period the time window, during which the tokens will be regenerated
     * @return Newly created {@link TokenBucket}
     * @see BandwidthLimit#of(long, Duration)
     */
    public static TokenBucket of(long limit, Duration period) {
        return new TokenBucket(BandwidthLimit.of(limit, period));
    }

    private final BandwidthLimit[] limits;

    /**
     * Defines throttling configuration comprised of zero or more bandwidth limits in accordance to
     * token-bucket algorithm.
     *
     * <h2>Multiple bandwidths:</h2>
     * It is possible to specify more than one bandwidth per bucket, and bucket will handle all bandwidth in
     * strongly atomic way. Strongly atomic means that token will be consumed from all bandwidth or from
     * nothing, in other words any token can not be partially consumed.
     * <br> Example of multiple bandwidth:
     * <pre>{@code
     * // Adds bandwidth that restricts to consume
     * // not often than 1000 tokens per 1 minute and
     * // not often than 100 tokens per second.
     * TokenBucketConfig config = TokenBucketConfig.builder()
     *      .limit(1000L, Duration.ofMinutes(1))
     *      .limit(100L, Duration.ofSeconds(1))
     *      .build()
     * }</pre>
     *
     * @param limits one or more bandwidth limits to be used by token-bucket algorithm
     */
    TokenBucket(BandwidthLimit... limits) {
        this.limits = requireNonNull(limits, "limits");
    }

    /**
     * Returns multiple limits applied to the bucket. This may be empty.
     * @return An array of {@link BandwidthLimit}
     */
    public BandwidthLimit[] limits() {
        return limits;
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("limits", limits)
                .toString();
    }

    /**
     * Selects lowest limit out of the limits configured, normalizing all rates to rate-per-second.
     */
    @Nullable
    BandwidthLimit lowestLimit() {
        @Nullable BandwidthLimit lowestLimit = null;
        for (BandwidthLimit limit : limits) {
            if (lowestLimit == null) {
                lowestLimit = limit;
            } else {
                if (Double.compare(limit.ratePerSecond(), lowestLimit.ratePerSecond()) < 0) {
                    lowestLimit = limit;
                }
            }
        }
        return lowestLimit;
    }

    /**
     * Returns a string representation of the multiple limits in the following format,
     * as per <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">RateLimit Header
     * Fields for HTTP</a>:
     * <pre>{@code
     * <lowest limit>, <first limit>;window=<first period(in seconds)>;burst=<first overdraftLimit>,
     *                 <second limit>;window=<second period(in seconds)>;burst=<second overdraftLimit>, etc.
     * }</pre>
     * For example: "100, 100;window=60;burst=1000, 5000;window=3600;burst=0".
     *
     * @return A {@link String} representation of the limits.
     */
    @Nullable
    String toSpecString() {
        @Nullable
        final BandwidthLimit lowestLimit = lowestLimit();
        if (limits.length == 0 || lowestLimit == null) {
            return null;
        }
        return lowestLimit.limit() + ", " + requireNonNull(TokenBucketSpec.toString(this));
    }
}
