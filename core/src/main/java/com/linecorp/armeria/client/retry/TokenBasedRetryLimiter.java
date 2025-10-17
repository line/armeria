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

import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;

final class TokenBasedRetryLimiter implements RetryLimiter {

    /**
     * The number of tokens starts at maxTokens. The token_count will always be between 0 and maxTokens.
     */
    private final int maxTokens;

    /**
     * The threshold. Retries are allowed if the token count is greater than the threshold
     */
    private final int threshold;

    final AtomicInteger tokenCount;

    TokenBasedRetryLimiter(int maxTokens, int threshold) {
        checkArgument(maxTokens > 0, "maxTokens must be positive: %s.", maxTokens);
        checkArgument(threshold >= 0 && threshold < maxTokens,
                      "invalid threshold: %s (>=0 && <%s)", threshold, maxTokens);
        this.maxTokens = maxTokens;
        this.threshold = threshold;
        tokenCount = new AtomicInteger(maxTokens);
    }

    @Override
    public boolean shouldRetry(ClientRequestContext ctx) {
        return tokenCount.get() > threshold;
    }

    @Override
    public void handleDecision(ClientRequestContext ctx, RetryDecision decision) {
        final int permits = (int) decision.permits();
        if (permits == 0) {
            return;
        }
        boolean updated;
        do {
            final int currentCount = tokenCount.get();
            int newCount = currentCount - permits;
            newCount = Math.max(newCount, 0);
            newCount = Math.min(newCount, maxTokens);
            updated = tokenCount.compareAndSet(currentCount, newCount);
        } while (!updated);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("maxTokens", maxTokens)
                          .add("threshold", threshold)
                          .add("tokenCount", tokenCount)
                          .toString();
    }
}
