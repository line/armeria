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

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class RetryLimiterTest {

    // ============================================================================
    // RetryRateLimiter.ofRateLimiter Tests
    // ============================================================================

    @Test
    void ofRateLimiter() {
        final RetryLimiter limiter = RetryLimiter.ofRateLimiter(2.5);

        assertThat(limiter).isInstanceOf(RetryRateLimiter.class);
        final RetryRateLimiter rateLimiter = (RetryRateLimiter) limiter;
        assertThat(rateLimiter.toString()).contains("permitsPerSecond=2.5");
    }

    @Test
    void ofGrpc() {
        final RetryLimiter limiter = RetryLimiter.ofGrpc(10.5f, 1.25f);

        assertThat(limiter).isInstanceOf(GrpcRetryLimiter.class);
        final GrpcRetryLimiter grpcLimiter = (GrpcRetryLimiter) limiter;
        assertThat(grpcLimiter.maxTokens).isEqualTo(10500); // 10.5 * 1000
        assertThat(grpcLimiter.threshold).isEqualTo(5250);  // 10.5 / 2 * 1000
        assertThat(grpcLimiter.tokenRatio).isEqualTo(1250); // 1.25 * 1000
        assertThat(grpcLimiter.retryableStatuses).containsExactly("14"); // UNAVAILABLE
    }

    @Test
    void ofGrpc_withAllParameters() {
        final RetryLimiter limiter = RetryLimiter.ofGrpc(20.5f, 2.25f, 8.75f, Arrays.asList(14, 13));

        assertThat(limiter).isInstanceOf(GrpcRetryLimiter.class);
        final GrpcRetryLimiter grpcLimiter = (GrpcRetryLimiter) limiter;
        assertThat(grpcLimiter.maxTokens).isEqualTo(20500); // 20.5 * 1000
        assertThat(grpcLimiter.threshold).isEqualTo(8750);  // 8.75 * 1000
        assertThat(grpcLimiter.tokenRatio).isEqualTo(2250); // 2.25 * 1000
        assertThat(grpcLimiter.retryableStatuses).containsExactlyInAnyOrder("14", "13");
    }

    @Test
    void ofRateLimiter_functionalTest() {
        final RetryLimiter limiter = RetryLimiter.ofRateLimiter(10.0); // High rate for testing

        // Should allow first retry (RateLimiter allows one initial permit)
        assertThat(limiter.shouldRetry(null, 1)).isTrue();
        // Second retry should be denied due to rate limiting
        assertThat(limiter.shouldRetry(null, 2)).isFalse();
    }

    @Test
    void ofGrpc_functionalTest() {
        final RetryLimiter limiter = RetryLimiter.ofGrpc(10.0f, 1.0f);

        // Should allow retries when tokens are above threshold
        assertThat(limiter.shouldRetry(null, 1)).isTrue();
        assertThat(limiter.shouldRetry(null, 2)).isTrue();
    }

    @Test
    void ofGrpc_withCustomThreshold_functionalTest() {
        final RetryLimiter limiter = RetryLimiter.ofGrpc(10.0f, 1.0f, 8.0f, Collections.singletonList(14));

        // Should allow retries when tokens are above threshold
        assertThat(limiter.shouldRetry(null, 1)).isTrue();
        assertThat(limiter.shouldRetry(null, 2)).isTrue();
    }
}
