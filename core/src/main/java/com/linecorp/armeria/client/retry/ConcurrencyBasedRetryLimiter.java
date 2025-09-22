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

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;

final class ConcurrencyBasedRetryLimiter implements RetryLimiter {

    private final long maxRequests;
    private final AtomicLong activeRequests = new AtomicLong();

    ConcurrencyBasedRetryLimiter(long maxRequests) {
        checkArgument(maxRequests > 0, "threshold must be positive: %s.", maxRequests);
        this.maxRequests = maxRequests;
    }

    @Override
    public boolean shouldRetry(ClientRequestContext ctx) {
        final long cnt = activeRequests.incrementAndGet();
        if (cnt > maxRequests) {
            activeRequests.decrementAndGet();
            return false;
        }
        ctx.log().whenComplete().thenRun(activeRequests::decrementAndGet);
        return true;
    }

    @Override
    public void handleDecision(ClientRequestContext ctx, RetryDecision decision) {
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("activeRequests", activeRequests)
                          .add("maxRequests", maxRequests)
                          .toString();
    }
}
