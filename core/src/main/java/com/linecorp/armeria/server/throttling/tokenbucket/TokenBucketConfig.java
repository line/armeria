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

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;

/**
 * Stores configuration of the Token-Bucket algorithm.
 */
public class TokenBucketConfig {

    @Nonnull
    private final BandwidthLimit[] limits;
    @Nonnull
    private final Duration retryAfterTimeout;

    /**
     * Defines throttling configuration comprised of one or more bandwidth limits in accordance to
     * token-bucket algorithm.
     *
     * <h3>Multiple bandwidths:</h3>
     * It is possible to specify more than one bandwidth per bucket,
     * and bucket will handle all bandwidth in strongly atomic way.
     * Strongly atomic means that token will be consumed from all bandwidth or from nothing,
     * in other words any token can not be partially consumed.
     * <br> Example of multiple bandwidth:
     * <pre>{@code
     * // Adds bandwidth that restricts to consume
     * // not often than 1000 tokens per 1 minute and
     * // not often than 100 tokens per second.
     * TokenBucketConfig config = TokenBucketConfig.builder()
     *      .limit(1000L, Duration.ofMinutes(1))
     *      .limit(100L, Duration.ofSeconds(1))
     *      .retryAfterTimeout(Duration.ofSeconds(10L))
     *      .build()
     * }</pre>
     *
     * @param retryAfterTimeout the client timeout to retry the attempt
     * @param limits one or more bandwidth limits to be used by token-bucket algorithm
     */
    TokenBucketConfig(@Nonnull Duration retryAfterTimeout, @Nonnull BandwidthLimit... limits) {
        requireNonNull(retryAfterTimeout, "retryAfterTimeout");
        if (retryAfterTimeout.isNegative() || retryAfterTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "retryAfterTimeout: " + retryAfterTimeout + " (expected: > 0)");
        }
        this.retryAfterTimeout = retryAfterTimeout;
        this.limits = requireNonNull(limits, "limits");
    }

    /**
     * Creates a new {@link TokenBucketConfigBuilder}.
     */
    public static TokenBucketConfigBuilder builder() {
        return new TokenBucketConfigBuilder();
    }

    /**
     *  Multiple limits applied to the bucket. This may be empty.
     * @return An array of {@link BandwidthLimit}
     */
    @Nonnull
    public BandwidthLimit[] limits() {
        return limits;
    }

    /**
     * When the bucket limits reached, the incoming requests will be replied
     * with {@link HttpStatus#TOO_MANY_REQUESTS} that carries {@link HttpHeaderNames#RETRY_AFTER} with
     * the value returned by this method.
     * @return A {@link Duration} after which the client may retry again.
     */
    @Nonnull
    public Duration retryAfterTimeout() {
        return retryAfterTimeout;
    }

    @Override
    public String toString() {
        return MoreObjects
                .toStringHelper(this)
                .add("limits", limits)
                .add("retryAfterTimeout", retryAfterTimeout)
                .toString();
    }
}
