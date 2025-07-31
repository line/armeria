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
import static com.linecorp.armeria.common.logging.RequestLogProperty.RESPONSE_HEADERS;
import static com.linecorp.armeria.common.logging.RequestLogProperty.RESPONSE_TRAILERS;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.common.InternalGrpcWebTrailers;

/**
 * Retry limiter based on a token bucket algorithm with 3 parameters:
 * <ul>
 *   <li>Max tokens: Max value of tokens that can be stored in the bucket</li>
 *   <li>Threshold: The min number tokens in the bucket required to allow retries to happen</li>
 *   <li>TokenRatio: The number of tokens that a successful attempt adds to the bucket.</li>
 * </ul>
 *
 * <p>The algorithm subtracts 1 token from the bucket for each retriable error and then, if we
 * can retry (due to timeouts and max attempts), it checks if the bucket is over the threshold.
 * On every successful request, we add X tokens where X equals to the token ratio.
 *
 * <p>In Grpc implementation, the threshold is hardcoded as half the capacity of the bucket but you can set your
 * own threshold here.
 *
 * <p>Internally, the implementation stores all values multiplied by 1000, some manipulation is required as
 * max tokens can be odd, and when setting the threshold to half of max tokens, rounding will be needed, this
 * way we keep full precision.
 *
 * <p>The implementation is heavily based on GRPC Java <a href="https://github.com/grpc/grpc-java/blob/94532a6b56076c56fb9278e9195bba1190a9260d/core/src/main/java/io/grpc/internal/RetriableStream.java#L1463">implementation</a>.
 */
public class GrpcRetryLimiter implements RetryLimiter {

    private static final int THREE_DECIMAL_PLACES_SCALE_UP = 1000;

    /**
     * 1000 times the maxTokens.
     * The number of tokens starts at maxTokens. The token_count will always be between 0 and maxTokens.
     */
    final int maxTokens;

    /**
     * Half of {@code maxTokens} or 1000 times the threshold.
     */
    final int threshold;

    /**
     * 1000 times the tokenRatio field.
     */
    final int tokenRatio;

    final Set<String> retryableStatuses;

    final AtomicInteger tokenCount = new AtomicInteger();

    /**
     * Default retry limiter based on GRPC implementation as described
     * <a href="https://github.com/grpc/proposal/blob/master/A6-client-retries.md#throttling-retry-attempts-and-hedged-rpcs">here</a>
     *
     * <p>This constructor builds a limiter configured to only ever allow retries when the bucket is at least
     * half filled, and only decrements tokens when the response status is UNAVAILABLE.
     *
     * <p>maxTokens and tokenRatio are multiplied by 1000 and converted to int for the internal
     * operations
     *
     * @param maxTokens Initial token count
     * @param tokenRatio Number of tokens a successful request increments
     */
    public GrpcRetryLimiter(float maxTokens, float tokenRatio) {
        // Validate inputs
        checkArgument(
                maxTokens > 0 && tokenRatio > 0,
                "maxTokens and tokenRatio must be positive: " + "maxTokens=" + maxTokens +
                ", tokenRatio=" + tokenRatio
        );
        // tokenRatio is up to 3 decimal places
        this.tokenRatio = (int) (tokenRatio * THREE_DECIMAL_PLACES_SCALE_UP);
        this.maxTokens = (int) (maxTokens * THREE_DECIMAL_PLACES_SCALE_UP);
        threshold = this.maxTokens / 2;
        // The default gRPC retry configuration only considers UNAVAILABLE(14) as a retriable error
        retryableStatuses = ImmutableSet.of("14");
        tokenCount.set(this.maxTokens);
    }

    /**
     * Constructs a {@link GrpcRetryLimiter} with the specified parameters.
     *
     * <p>maxTokens, tokenRatio and threshold are multiplied by 1000 and converted to int for the internal
     * operations
     *
     * @param maxTokens the initial token count (must be positive)
     * @param tokenRatio the number of tokens a successful request increments (must be positive)
     * @param threshold the minimum token count required to allow a retry (must be positive and less than or
     *     equal to {@code maxTokens})
     * @param retryableStatuses the collection of gRPC status codes (as integers) that are considered
     *     retryable (must not be null or empty)
     * @throws IllegalArgumentException if any argument is invalid
     */
    public GrpcRetryLimiter(float maxTokens, float tokenRatio, float threshold,
                            Collection<Integer> retryableStatuses) {
        // Validate inputs
        checkArgument(
                maxTokens > 0 && tokenRatio > 0 && threshold >= 0,
                "maxTokens, tokenRatio, and threshold must be positive: " + "maxTokens=" + maxTokens +
                ", tokenRatio=" + tokenRatio + ", threshold=" + threshold
        );
        checkArgument(threshold <= maxTokens,
                      "threshold must be less than or equal to maxTokens: " + threshold + " > " + maxTokens
        );
        checkArgument(retryableStatuses != null && !retryableStatuses.isEmpty(),
                      "retryableStatuses cannot be null or empty: " + retryableStatuses);

        // tokenRatio is up to 3 decimal places
        this.tokenRatio = (int) (tokenRatio * THREE_DECIMAL_PLACES_SCALE_UP);
        this.maxTokens = (int) (maxTokens * THREE_DECIMAL_PLACES_SCALE_UP);
        this.threshold = (int) (threshold * THREE_DECIMAL_PLACES_SCALE_UP);
        // Convert statuses to String so we can use them later
        this.retryableStatuses = retryableStatuses.stream()
                                                  .filter(Objects::nonNull)
                                                  .map(String::valueOf)
                                                  .collect(Collectors.toSet());

        // Ensure we have at least one valid status after filtering nulls
        if (this.retryableStatuses.isEmpty()) {
            throw new IllegalArgumentException("retryableStatuses cannot contain only null values");
        }

        tokenCount.set(this.maxTokens);
    }

    /**
     * Determines whether a retry should be allowed based on the current token count and threshold.
     *
     * @param ctx the request context
     * @param numAttemptsSoFar the number of attempts made so far
     * @return {@code true} if a retry is allowed
     */
    @Override
    public boolean shouldRetry(ClientRequestContext ctx, int numAttemptsSoFar) {
        return tokenCount.get() > threshold;
    }

    /**
     * This function handles the token increase or decrease.
     * In GRPC retry throttling implementation, there are 3 cases:
     * <ul>
     *   <li>When a response is received with a retryable statuses, deduct a token</li>
     *   <li>When a response is received with any other statuses, refill by token ration</li>
     *   <li>When a response is not received, the token count does not change</li>
     * </ul>
     * @param ctx full request context that also includes response information
     * @param requestLog reduced context with request and response information
     * @param numAttemptsSoFar number of attempts (starting with 1)
     */
    @Override
    public void onCompletedAttempt(ClientRequestContext ctx, RequestLog requestLog, int numAttemptsSoFar) {
        // Check if response headers and trailers are available if not we don't have a valid response
        if (!requestLog.isAvailable(RESPONSE_HEADERS, RESPONSE_TRAILERS)) {
            return;
        }

        // Extract the headers to be able to evaluate the gRPC status
        // Check HTTP trailers first, because most gRPC responses have non-empty payload + trailers.
        HttpHeaders maybeGrpcTrailers = requestLog.responseTrailers();
        if (!maybeGrpcTrailers.contains("grpc-status")) {
            // Check HTTP headers secondly.
            maybeGrpcTrailers = requestLog.responseHeaders();
            if (!maybeGrpcTrailers.contains("grpc-status")) {
                // Check gRPC Web trailers lastly, because gRPC Web is the least used protocol
                // in reality.
                maybeGrpcTrailers = InternalGrpcWebTrailers.get(ctx);
            }
        }
        // If there are no headers, it is not a valid response so no changes.
        if (maybeGrpcTrailers == null) {
            return;
        }
        // Check if the status is one of the retriable ones, if it is we should deduct a token if not
        // add tokenRatio tokens.
        final String status = maybeGrpcTrailers.get("grpc-status");
        final boolean decrement = retryableStatuses.contains(status);

        boolean updated;
        do {
            final int currentCount = tokenCount.get();
            if (decrement) {
                if (currentCount == 0) {
                    break;
                }
                final int decremented = currentCount - THREE_DECIMAL_PLACES_SCALE_UP;
                updated = tokenCount.compareAndSet(currentCount, Math.max(decremented, 0));
            } else {
                if (currentCount == maxTokens) {
                    break;
                }
                final int incremented = currentCount + tokenRatio;
                updated = tokenCount.compareAndSet(currentCount, Math.min(incremented, maxTokens));
            }
        } while (!updated);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("maxTokens", maxTokens / (double) THREE_DECIMAL_PLACES_SCALE_UP)
                .add("threshold", threshold / (double) THREE_DECIMAL_PLACES_SCALE_UP)
                .add("tokenRatio", tokenRatio / (double) THREE_DECIMAL_PLACES_SCALE_UP)
                .add("retryableStatuses", retryableStatuses)
                .add("currentTokenCount", tokenCount.get() / (double) THREE_DECIMAL_PLACES_SCALE_UP)
                .toString();
    }
}
