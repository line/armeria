/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.retry.limiter;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.RateLimiter;

import com.linecorp.armeria.client.ClientRequestContext;

/**
 * A Retry limiter implementation that limits based on a rate limiter.
 *
 * <p>This limiter allows a flat number of retries per second
 */
public class RetryRateLimiter implements RetryLimiter {

    private final RateLimiter rateLimiter;

    /**
     * Creates a new {@link RetryRateLimiter} with the specified number of permits per second.
     *
     * @param permitsPerSecond the number of retry permits allowed per second; must be positive
     */
    public RetryRateLimiter(double permitsPerSecond) {
        checkArgument(permitsPerSecond > 0, "permitsPerSecond must be positive: %s", permitsPerSecond);
        rateLimiter = RateLimiter.create(permitsPerSecond);
    }

    /**
     * Returns {@code true} if a permit is available from the rate limiter and acquires it.
     * Otherwise, returns {@code false} to prevent retry.
     *
     * @param ctx the request context
     * @param numAttemptsSoFar the number of attempts made so far
     * @return {@code true} if a retry is allowed
     */
    @Override
    public boolean shouldRetry(ClientRequestContext ctx, int numAttemptsSoFar) {
        return rateLimiter.tryAcquire();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("permitsPerSecond", rateLimiter.getRate())
                .toString();
    }
}
