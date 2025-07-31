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
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;

class GrpcRetryLimiterTest {

    private final ClientRequestContext ctx = mock(ClientRequestContext.class);

    private static HttpHeaders createGrpcHeaders(String status) {
        return HttpHeaders.builder()
                          .add("grpc-status", status)
                          .build();
    }

    private static HttpHeaders createEmptyHeaders() {
        return HttpHeaders.builder().build();
    }

    private static ResponseHeaders createResponseHeaders(String status) {
        return ResponseHeaders.builder(200)
                              .add("grpc-status", status)
                              .build();
    }

    private static ResponseHeaders createEmptyResponseHeaders() {
        return ResponseHeaders.of(200);
    }

    // ============================================================================
    // Constructor Tests
    // ============================================================================

    @Test
    void defaultConstructor() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);

        assertThat(limiter.maxTokens).isEqualTo(10000); // 10 * 1000
        assertThat(limiter.threshold).isEqualTo(5000); // 10 / 2 * 1000
        assertThat(limiter.tokenRatio).isEqualTo(1000); // 1 * 1000
        assertThat(limiter.retryableStatuses).containsExactly("14"); // UNAVAILABLE
        assertThat(limiter.tokenCount.get()).isEqualTo(10000); // maxTokens
    }

    @Test
    void defaultConstructor_withFloatValues() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.5f, 1.25f);

        assertThat(limiter.maxTokens).isEqualTo(10500); // 10.5 * 1000
        assertThat(limiter.threshold).isEqualTo(5250); // 10.5 / 2 * 1000
        assertThat(limiter.tokenRatio).isEqualTo(1250); // 1.25 * 1000
        assertThat(limiter.retryableStatuses).containsExactly("14"); // UNAVAILABLE
        assertThat(limiter.tokenCount.get()).isEqualTo(10500); // maxTokens
    }

    @Test
    void customConstructor() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(20.0f, 2.0f, 8.0f, Arrays.asList(14, 13));

        assertThat(limiter.maxTokens).isEqualTo(20000); // 20 * 1000
        assertThat(limiter.threshold).isEqualTo(8000);
        assertThat(limiter.tokenRatio).isEqualTo(2000); // 2 * 1000
        assertThat(limiter.retryableStatuses).containsExactlyInAnyOrder("14", "13");
        assertThat(limiter.tokenCount.get()).isEqualTo(20000); // maxTokens
    }

    @Test
    void customConstructor_withFloatValues() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(20.5f, 2.25f, 8.75f, Arrays.asList(14, 13));

        assertThat(limiter.maxTokens).isEqualTo(20500); // 20.5 * 1000
        assertThat(limiter.threshold).isEqualTo(8750); // 8.75 * 1000
        assertThat(limiter.tokenRatio).isEqualTo(2250); // 2.25 * 1000
        assertThat(limiter.retryableStatuses).containsExactlyInAnyOrder("14", "13");
        assertThat(limiter.tokenCount.get()).isEqualTo(20500); // maxTokens
    }

    @Test
    void constructor_withThresholdEqualToMaxTokens() {
        // This should be valid
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f, 10.0f,
                                                              Collections.singletonList(14));
        assertThat(limiter).isNotNull();
        assertThat(limiter.maxTokens).isEqualTo(10000);
        assertThat(limiter.threshold).isEqualTo(10000);
    }

    // ============================================================================
    // Constructor Validation Tests
    // ============================================================================

    @Test
    void defaultConstructor_withZeroMaxTokens() {
        final String error =
                "maxTokens and tokenRatio must be positive: maxTokens=0.0, tokenRatio=1.0";
        assertThatThrownBy(() -> new GrpcRetryLimiter(0.0f, 1.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(error);
    }

    @Test
    void defaultConstructor_withZeroTokenRatio() {
        final String error =
                "maxTokens and tokenRatio must be positive: maxTokens=1.0, tokenRatio=0.0";
        assertThatThrownBy(() -> new GrpcRetryLimiter(1.0f, 0.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(error);
    }

    @Test
    void defaultConstructor_withNegativeTokenRatio() {
        final String error =
                "maxTokens and tokenRatio must be positive: maxTokens=1.0, tokenRatio=-1.0";
        assertThatThrownBy(() -> new GrpcRetryLimiter(1.0f, -1.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(error);
    }

    @Test
    void constructor_withZeroMaxTokens() {
        final String error = "maxTokens, tokenRatio, and threshold must be positive:";
        assertThatThrownBy(() -> new GrpcRetryLimiter(0.0f, 1.0f, 5.0f, Collections.singletonList(14)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(error);
    }

    @Test
    void constructor_withZeroTokenRatio() {
        final String error = "maxTokens, tokenRatio, and threshold must be positive:";
        assertThatThrownBy(() -> new GrpcRetryLimiter(1.0f, 0.0f, 5.0f, Collections.singletonList(14)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(error);
    }

    @Test
    void constructor_withNegativeThreshold() {
        final String error = "maxTokens, tokenRatio, and threshold must be positive:";
        assertThatThrownBy(() -> new GrpcRetryLimiter(1.0f, 1.0f, -1.0f, Collections.singletonList(14)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(error);
    }

    @Test
    void constructor_withThresholdGreaterThanMaxTokens() {
        assertThatThrownBy(() -> new GrpcRetryLimiter(10.0f, 1.0f, 15.0f, Collections.singletonList(14)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("threshold must be less than or equal to maxTokens: 15.0 > 10.0");
    }

    @Test
    void constructor_withNullRetryableStatuses() {
        assertThatThrownBy(() -> new GrpcRetryLimiter(10.0f, 1.0f, 5.0f, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retryableStatuses cannot be null or empty: null");
    }

    @Test
    void constructor_withEmptyRetryableStatuses() {
        assertThatThrownBy(() -> new GrpcRetryLimiter(10.0f, 1.0f, 5.0f, Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retryableStatuses cannot be null or empty: []");
    }

    @Test
    void constructor_withNullValuesInRetryableStatuses() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f, 5.0f, Arrays.asList(14, null, 13));

        assertThat(limiter.retryableStatuses).containsExactlyInAnyOrder("14", "13");
    }

    @Test
    void constructor_withOnlyNullValuesInRetryableStatuses() {
        assertThatThrownBy(() -> new GrpcRetryLimiter(10.0f, 1.0f, 5.0f, Arrays.asList(null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retryableStatuses cannot contain only null values");
    }

    // ============================================================================
    // shouldRetry Tests
    // ============================================================================

    @Test
    void retryAllowed_whenTokensAboveThreshold() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);

        // Token count starts at maxTokens (10000), threshold is 5
        assertThat(limiter.shouldRetry(ctx, 2)).isTrue();
        assertThat(limiter.shouldRetry(ctx, 3)).isTrue();
    }

    @Test
    void retryNotAllowed_whenTokensAtThreshold() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f, 5.0f, Collections.singletonList(14));

        // Set token count to threshold
        limiter.tokenCount.set(5000);

        assertThat(limiter.shouldRetry(ctx, 2)).isFalse();
    }

    @Test
    void retryNotAllowed_whenTokensBelowThreshold() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f, 5.0f, Collections.singletonList(14));

        // Set token count below threshold
        limiter.tokenCount.set(4000);

        assertThat(limiter.shouldRetry(ctx, 2)).isFalse();
    }

    // ============================================================================
    // onCompletedAttempt Tests
    // ============================================================================

    @Test
    void onCompletedAttempt_withRetryableStatus_decrementsTokens() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);
        final RequestLog requestLog = mock(RequestLog.class);
        final HttpHeaders headers = createGrpcHeaders("14"); // UNAVAILABLE

        when(requestLog.responseTrailers()).thenReturn(headers);
        when(requestLog.isAvailable(RequestLogProperty.RESPONSE_HEADERS, RequestLogProperty.RESPONSE_TRAILERS))
                .thenReturn(true);

        final int initialTokens = limiter.tokenCount.get();
        limiter.onCompletedAttempt(ctx, requestLog, 1);

        assertThat(limiter.tokenCount.get()).isEqualTo(initialTokens - 1000);
    }

    @Test
    void onCompletedAttempt_withNonRetryableStatus_incrementsTokens() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);
        final RequestLog requestLog = mock(RequestLog.class);
        final HttpHeaders headers = createGrpcHeaders("0"); // OK

        when(requestLog.responseTrailers()).thenReturn(headers);
        when(requestLog.isAvailable(RequestLogProperty.RESPONSE_HEADERS, RequestLogProperty.RESPONSE_TRAILERS))
                .thenReturn(true);

        // Set token count to less than max to allow increment
        limiter.tokenCount.set(5000);

        final int initialTokens = limiter.tokenCount.get();
        limiter.onCompletedAttempt(ctx, requestLog, 1);

        assertThat(limiter.tokenCount.get()).isEqualTo(initialTokens + 1000);
    }

    @Test
    void onCompletedAttempt_withException_doesNotChangeTokens() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);
        final RequestLog requestLog = mock(RequestLog.class);
        when(requestLog.isAvailable(RequestLogProperty.RESPONSE_HEADERS, RequestLogProperty.RESPONSE_TRAILERS))
                .thenReturn(false);

        final int initialTokens = limiter.tokenCount.get();
        limiter.onCompletedAttempt(ctx, requestLog, 1);

        assertThat(limiter.tokenCount.get()).isEqualTo(initialTokens);
    }

    @Test
    void onCompletedAttempt_withNoGrpcStatus_doesNotChangeTokens() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);
        final RequestLog requestLog = mock(RequestLog.class);
        final HttpHeaders headers = createEmptyHeaders(); // No grpc-status

        when(requestLog.responseTrailers()).thenReturn(headers);
        when(requestLog.responseHeaders()).thenReturn(createEmptyResponseHeaders());
        when(requestLog.isAvailable(RequestLogProperty.RESPONSE_HEADERS, RequestLogProperty.RESPONSE_TRAILERS))
                .thenReturn(true);

        final int initialTokens = limiter.tokenCount.get();
        limiter.onCompletedAttempt(ctx, requestLog, 1);

        assertThat(limiter.tokenCount.get()).isEqualTo(initialTokens);
    }

    @Test
    void onCompletedAttempt_withGrpcStatusInHeaders() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);
        final RequestLog requestLog = mock(RequestLog.class);
        final HttpHeaders trailers = createEmptyHeaders(); // No grpc-status in trailers
        final ResponseHeaders headers = createResponseHeaders("14"); // UNAVAILABLE in headers

        when(requestLog.responseTrailers()).thenReturn(trailers);
        when(requestLog.responseHeaders()).thenReturn(headers);
        when(requestLog.isAvailable(RequestLogProperty.RESPONSE_HEADERS, RequestLogProperty.RESPONSE_TRAILERS))
                .thenReturn(true);

        final int initialTokens = limiter.tokenCount.get();
        limiter.onCompletedAttempt(ctx, requestLog, 1);

        assertThat(limiter.tokenCount.get()).isEqualTo(initialTokens - 1000);
    }

    @Test
    void onCompletedAttempt_tokenCountNeverGoesBelowZero() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);
        final RequestLog requestLog = mock(RequestLog.class);
        final HttpHeaders headers = createGrpcHeaders("14"); // UNAVAILABLE

        when(requestLog.responseTrailers()).thenReturn(headers);
        when(requestLog.isAvailable(RequestLogProperty.RESPONSE_HEADERS, RequestLogProperty.RESPONSE_TRAILERS))
                .thenReturn(true);

        // Set token count to 0
        limiter.tokenCount.set(0);

        limiter.onCompletedAttempt(ctx, requestLog, 1);

        assertThat(limiter.tokenCount.get()).isEqualTo(0);
    }

    @Test
    void onCompletedAttempt_tokenCountNeverExceedsMaxTokens() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);
        final RequestLog requestLog = mock(RequestLog.class);
        final HttpHeaders headers = createGrpcHeaders("0"); // OK

        when(requestLog.responseTrailers()).thenReturn(headers);
        when(requestLog.isAvailable(RequestLogProperty.RESPONSE_HEADERS, RequestLogProperty.RESPONSE_TRAILERS))
                .thenReturn(true);

        // Set token count to max
        limiter.tokenCount.set(limiter.maxTokens);

        limiter.onCompletedAttempt(ctx, requestLog, 1);

        assertThat(limiter.tokenCount.get()).isEqualTo(limiter.maxTokens);
    }

    // ============================================================================
    // Concurrency Tests
    // ============================================================================

    @Test
    void onCompletedAttempt_concurrentAccess() throws InterruptedException {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.0f, 1.0f);
        final RequestLog requestLog = mock(RequestLog.class);
        final HttpHeaders headers = createGrpcHeaders("14"); // UNAVAILABLE

        when(requestLog.responseTrailers()).thenReturn(headers);
        when(requestLog.isAvailable(RequestLogProperty.RESPONSE_HEADERS, RequestLogProperty.RESPONSE_TRAILERS))
                .thenReturn(true);

        // Start with max tokens
        limiter.tokenCount.set(limiter.maxTokens);

        // Create thread pool for concurrent access testing
        final int threadCount = 10;
        final int attemptsPerThread = 100;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < attemptsPerThread; j++) {
                        limiter.onCompletedAttempt(ctx, requestLog, 1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify final token count is correct (maxTokens - 1000 * threadCount * attemptsPerThread)
        final int expectedTokens = limiter.maxTokens - (1000 * threadCount * attemptsPerThread);
        assertThat(limiter.tokenCount.get()).isEqualTo(Math.max(0, expectedTokens));
    }

    @Test
    void grpcRetryLimiter_toString() {
        final GrpcRetryLimiter limiter = new GrpcRetryLimiter(10.5f, 1.25f, 5.75f, Arrays.asList(14));
        final String toString = limiter.toString();

        assertThat(toString).contains("GrpcRetryLimiter");
        assertThat(toString).contains("maxTokens=10.5");
        assertThat(toString).contains("threshold=5.75");
        assertThat(toString).contains("tokenRatio=1.25");
        assertThat(toString).contains("retryableStatuses=[14]");
        assertThat(toString).contains("currentTokenCount=10.5");
        // After some token changes (simulate by directly setting)
        limiter.tokenCount.set(5500); // 5.5 tokens
        final String updatedToString = limiter.toString();
        assertThat(updatedToString).contains("currentTokenCount=5.5");
    }
}
