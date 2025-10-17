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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;

class RetryLimiterTest {

    @Test
    void concurrencyLimitingAllowsRetriesWithinLimit() {
        final RetryLimiter limiter = RetryLimiter.concurrencyLimiting(10);
        final ClientRequestContext ctx = ctx();

        assertThat(limiter.shouldRetry(ctx)).isTrue();
        assertThat(limiter.shouldRetry(ctx)).isTrue();
    }

    @Test
    void concurrencyLimitingBlocksExcessiveRetries() throws Exception {
        final RetryLimiter limiter = RetryLimiter.concurrencyLimiting(1);
        final ClientRequestContext ctx1 = ctx();
        final ClientRequestContext ctx2 = ctx();

        assertThat(limiter.shouldRetry(ctx1)).isTrue();
        assertThat(limiter.shouldRetry(ctx2)).isFalse();

        ctx1.cancel();
        // cancel doesn't synchronously complete futures, so we wait until the limiter is available again
        await().untilAsserted(() -> assertThat(limiter.shouldRetry(ctx2)).isTrue());
    }

    @Test
    void concurrencyLimitingIgnoresDecisionHandling() {
        final RetryLimiter limiter = RetryLimiter.concurrencyLimiting(10);
        final ClientRequestContext ctx = ctx();
        final RetryDecision zeroDecision = RetryDecision.retry(Backoff.ofDefault(), 0.0);
        final RetryDecision positiveDecision = RetryDecision.retry(Backoff.ofDefault(), 1.0);
        final RetryDecision negativeDecision = RetryDecision.retry(Backoff.ofDefault(), -1.0);

        assertThat(limiter.shouldRetry(ctx)).isTrue();
        limiter.handleDecision(ctx, zeroDecision);
        limiter.handleDecision(ctx, positiveDecision);
        limiter.handleDecision(ctx, negativeDecision);
        assertThat(limiter.shouldRetry(ctx)).isTrue();
    }

    @Test
    void tokenBasedInitiallyAllowsRetries() {
        final RetryLimiter limiter = RetryLimiter.tokenBased(10, 1);
        final ClientRequestContext ctx = ctx();

        assertThat(limiter.shouldRetry(ctx)).isTrue();
    }

    @Test
    void tokenBasedBlocksWhenTokensExhausted() {
        final RetryLimiter limiter = RetryLimiter.tokenBased(10, 5);
        final ClientRequestContext ctx = ctx();
        final RetryDecision positiveDecision = RetryDecision.retry(Backoff.ofDefault(), 1.0);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.shouldRetry(ctx)).isTrue();
            limiter.handleDecision(ctx, positiveDecision);
        }

        assertThat(limiter.shouldRetry(ctx)).isFalse();
    }

    @Test
    void tokenBasedReplenishesTokens() {
        final RetryLimiter limiter = RetryLimiter.tokenBased(10, 5);
        final ClientRequestContext ctx = ctx();
        final RetryDecision positiveDecision = RetryDecision.retry(Backoff.ofDefault(), 1.0);
        final RetryDecision negativeDecision = RetryDecision.retry(Backoff.ofDefault(), -1.0);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.shouldRetry(ctx)).isTrue();
            limiter.handleDecision(ctx, positiveDecision);
        }
        // blocked
        assertThat(limiter.shouldRetry(ctx)).isFalse();

        // replenish tokens
        limiter.handleDecision(ctx, negativeDecision);
        assertThat(limiter.shouldRetry(ctx)).isTrue();
    }

    @Test
    void tokenBasedIgnoresZeroPermits() {
        final RetryLimiter limiter = RetryLimiter.tokenBased(2, 1);
        final ClientRequestContext ctx = ctx();
        final RetryDecision zeroDecision = RetryDecision.retry(Backoff.ofDefault(), 0.0);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.shouldRetry(ctx)).isTrue();
            limiter.handleDecision(ctx, zeroDecision);
        }
    }

    private static ClientRequestContext ctx() {
        return ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }
}
