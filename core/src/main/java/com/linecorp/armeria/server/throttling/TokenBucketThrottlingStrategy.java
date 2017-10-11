/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server.throttling;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link ThrottlingStrategy} that throttles a request using
 * <a href="http://en.wikipedia.org/wiki/Token_bucket">Token Bucket</a>.
 */
public final class TokenBucketThrottlingStrategy<T extends Request> extends ThrottlingStrategy<T> {
    private final int tokenBucketCapacity;
    private final long refillInterval;
    private volatile int tokensInBucket;
    private volatile long nextRefillTime;

    /**
     * Creates a new strategy.
     */
    public TokenBucketThrottlingStrategy(int tokenBucketCapacity, int averageQPS, Duration refillInterval) {
        checkArgument(tokenBucketCapacity > 0, "tokenBucketCapacity: %s (expected: > 0)", tokenBucketCapacity);
        checkArgument(averageQPS > 0, "averageQPS: %s (expected: > 0)", averageQPS);
        requireNonNull(refillInterval, "refillInterval");
        this.tokenBucketCapacity = tokenBucketCapacity;
        this.refillInterval = LongMath.saturatedMultiply(averageQPS, refillInterval.toNanos());
    }

    @Override
    public CompletableFuture<Boolean> shouldThrottle(ServiceRequestContext ctx, T request) {
        refillTokenIfNeeded();
        if (tokensInBucket < 1) { // bucket is empty.
            return completedFuture(true);
        }

        tokensInBucket--;
        return completedFuture(false);
    }

    private void refillTokenIfNeeded() {
        long currentTime = System.nanoTime();
        if (currentTime < nextRefillTime) {
            return;
        }

        tokensInBucket = tokenBucketCapacity;
        nextRefillTime = currentTime + refillInterval;
    }
}
