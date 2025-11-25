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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;

class RetryRateLimiterTest {

    private final ClientRequestContext ctx = mock(ClientRequestContext.class);

    @Test
    void constructor_withPositiveRate() {
        final RetryRateLimiter limiter = new RetryRateLimiter(10.0);

        // Should not throw any exception
        assertThat(limiter).isNotNull();
    }

    @Test
    void constructor_withZeroRate() {
        assertThatThrownBy(() -> new RetryRateLimiter(0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_withNegativeRate() {
        assertThatThrownBy(() -> new RetryRateLimiter(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRetry_withHighRate_shouldAllowOneInitialRetry() {
        final RetryRateLimiter limiter = new RetryRateLimiter(100.0); // 100 permits per second

        // With any rate, only the first retry should be allowed initially
        assertThat(limiter.shouldRetry(ctx, 2)).isTrue();
        assertThat(limiter.shouldRetry(ctx, 3)).isFalse();
        assertThat(limiter.shouldRetry(ctx, 4)).isFalse();
    }

    @Test
    void shouldRetry_withLowRate_shouldLimitRetries() {
        final RetryRateLimiter limiter = new RetryRateLimiter(1.0); // 1 permit per second

        // First retry should be allowed
        assertThat(limiter.shouldRetry(ctx, 2)).isTrue();

        // Second retry immediately after should be denied due to rate limiting
        assertThat(limiter.shouldRetry(ctx, 3)).isFalse();
    }

    @Test
    void shouldRetry_withVeryHighRate_shouldStillLimitInitialRetries() {
        final RetryRateLimiter limiter = new RetryRateLimiter(1000.0); // 1000 permits per second

        // Even with high rate, only the first retry should be allowed initially
        assertThat(limiter.shouldRetry(ctx, 2)).isTrue();
        assertThat(limiter.shouldRetry(ctx, 3)).isFalse();
        assertThat(limiter.shouldRetry(ctx, 4)).isFalse();
    }

    @Test
    void shouldRetry_rateLimitingBehavior() throws InterruptedException {
        final RetryRateLimiter limiter = new RetryRateLimiter(2.0); // 2 permits per second

        // First retry should be allowed
        assertThat(limiter.shouldRetry(ctx, 2)).isTrue();

        // Second retry immediately after should be denied
        assertThat(limiter.shouldRetry(ctx, 3)).isFalse();

        // Wait for 0.5 seconds + some tolerance to allow rate limiter to refill
        //(2 permits per second = 0.5 seconds per permit)
        Thread.sleep(500 + 100);

        // Should allow one more retry after waiting
        assertThat(limiter.shouldRetry(ctx, 4)).isTrue();

        // But the next one should be denied again
        assertThat(limiter.shouldRetry(ctx, 5)).isFalse();
    }

    @Test
    void shouldRetry_concurrentAccess() throws InterruptedException {
        final RetryRateLimiter limiter = new RetryRateLimiter(10.0); // 10 permits per second

        final int threadCount = 5;
        final int attemptsPerThread = 10;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger successfulRetries = new AtomicInteger(0);
        final AtomicInteger failedRetries = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (limiter.shouldRetry(ctx, j)) {
                            successfulRetries.incrementAndGet();
                        } else {
                            failedRetries.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // With 10 permits per second and 5 threads making 10 attempts each,
        // we should have some successful and some failed retries
        assertThat(successfulRetries.get()).isGreaterThan(0);
        assertThat(failedRetries.get()).isGreaterThan(0);
        assertThat(successfulRetries.get() + failedRetries.get()).isEqualTo(threadCount * attemptsPerThread);
    }

    @Test
    void shouldRetry_withFractionalRate() {
        final RetryRateLimiter limiter = new RetryRateLimiter(0.5); // 0.5 permits per second

        // First retry should be allowed
        assertThat(limiter.shouldRetry(ctx, 2)).isTrue();

        // Second retry immediately after should be denied due to rate limiting
        assertThat(limiter.shouldRetry(ctx, 3)).isFalse();

        // Wait for 2 seconds + some tolerance to allow rate limiter to refill
        //(0.5 permits per second = 2 seconds per permit)
        try {
            Thread.sleep(2000 + 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Should allow one more retry after waiting
        assertThat(limiter.shouldRetry(ctx, 4)).isTrue();
    }

    @Test
    void multipleInstances_areIndependent() {
        final RetryRateLimiter limiter1 = new RetryRateLimiter(1.0);
        final RetryRateLimiter limiter2 = new RetryRateLimiter(1.0);

        // Both limiters should allow their first retry
        assertThat(limiter1.shouldRetry(ctx, 2)).isTrue();
        assertThat(limiter2.shouldRetry(ctx, 2)).isTrue();

        // Both limiters should deny their second retry due to rate limiting
        assertThat(limiter1.shouldRetry(ctx, 3)).isFalse();
        assertThat(limiter2.shouldRetry(ctx, 3)).isFalse();
    }
}
