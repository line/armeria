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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

/**
 * Builds a {@link TokenBucketConfig} instance using builder pattern.
 */
public class TokenBucketConfigBuilder {
    private static final BandwidthLimit[] NO_BANDWIDTH_LIMITS = {};
    private static final Duration DEFAULT_RETRY_AFTER_TIMEOUT = Duration.ofSeconds(10L);

    private List<BandwidthLimit> limits = Collections.emptyList();
    private Duration retryAfterTimeout = DEFAULT_RETRY_AFTER_TIMEOUT;

    TokenBucketConfigBuilder() { // prevent public access
    }

    /**
     * Adds a number of {@link BandwidthLimit}.
     */
    public TokenBucketConfigBuilder limits(@Nonnull BandwidthLimit... limits) {
        requireNonNull(limits, "limits");
        if (this.limits.isEmpty()) {
            this.limits = new ArrayList<>(2);
        }
        this.limits.addAll(Arrays.asList(limits));
        return this;
    }

    /**
     * Adds new {@link BandwidthLimit}.
     */
    public TokenBucketConfigBuilder limit(long limit, long overdraftLimit, Duration period) {
        return limits(new BandwidthLimit(limit, overdraftLimit, period));
    }

    /**
     * Adds new {@link BandwidthLimit}.
     */
    public TokenBucketConfigBuilder limit(long limit, Duration period) {
        return limits(new BandwidthLimit(limit, period));
    }

    /**
     * Sets Retry-Timeout configuration.
     */
    public TokenBucketConfigBuilder retryAfterTimeout(Duration retryAfterTimeout) {
        this.retryAfterTimeout = retryAfterTimeout;
        return this;
    }

    /**
     * Sets Retry-Timeout configuration.
     */
    public TokenBucketConfigBuilder retryAfterTimeout(long retryAfterSeconds) {
        retryAfterTimeout(Duration.ofSeconds(retryAfterSeconds));
        return this;
    }

    /**
     * Builds {@link TokenBucketConfig}.
     */
    public TokenBucketConfig build() {
        return new TokenBucketConfig(retryAfterTimeout,
                                     limits.isEmpty() ? NO_BANDWIDTH_LIMITS
                                                      : limits.toArray(NO_BANDWIDTH_LIMITS));
    }
}
