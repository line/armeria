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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds a {@link TokenBucket} instance.
 */
@UnstableApi
public final class TokenBucketBuilder {
    private static final BandwidthLimit[] NO_BANDWIDTH_LIMITS = {};

    private final ImmutableList.Builder<BandwidthLimit> limitsBuilder = ImmutableList.builder();

    TokenBucketBuilder() {} // prevent public access

    /**
     * Adds a number of {@link BandwidthLimit}.
     */
    public TokenBucketBuilder limits(BandwidthLimit... limits) {
        requireNonNull(limits, "limits");
        return limits(ImmutableList.copyOf(limits));
    }

    /**
     * Adds a number of {@link BandwidthLimit}.
     */
    public TokenBucketBuilder limits(Iterable<BandwidthLimit> limits) {
        requireNonNull(limits, "limits");
        limitsBuilder.addAll(limits);
        return this;
    }

    /**
     * Adds new {@link BandwidthLimit}.
     */
    public TokenBucketBuilder limit(long limit, long overdraftLimit, long initialSize, Duration period) {
        return limits(BandwidthLimit.of(limit, overdraftLimit, initialSize, period));
    }

    /**
     * Adds new {@link BandwidthLimit}.
     */
    public TokenBucketBuilder limit(long limit, long overdraftLimit, Duration period) {
        return limits(BandwidthLimit.of(limit, overdraftLimit, period));
    }

    /**
     * Adds new {@link BandwidthLimit}.
     */
    public TokenBucketBuilder limit(long limit, Duration period) {
        return limits(BandwidthLimit.of(limit, period));
    }

    /**
     * Returns a newly-created {@link TokenBucket} based on the set of limits configured for this builder.
     */
    public TokenBucket build() {
        final ImmutableList<BandwidthLimit> limits = limitsBuilder.build();
        return new TokenBucket(limits.isEmpty() ? NO_BANDWIDTH_LIMITS
                                                : limits.toArray(NO_BANDWIDTH_LIMITS));
    }
}
