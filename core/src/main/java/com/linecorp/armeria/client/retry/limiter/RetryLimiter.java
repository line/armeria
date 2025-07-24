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

import java.util.Collection;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.logging.RequestLog;

/**
 * A strategy interface for limiting retries in Armeria clients.
 * Implementations decide whether a retry should be allowed and can update internal state after each attempt.
 *
 * <p>Implementations should be thread-safe and designed to handle concurrent calls to both methods.
 * They can be called concurrently by multiple threads if there are multiple calls using the same client
 * instance or if the limiter is shared across multiple clients.
 *
 * <p>Handle your errors carefully. If the implementation throws an exception it will be logged and the retry
 * will be allowed.
 */
@FunctionalInterface
public interface RetryLimiter {

    /**
     * Creates a new {@link RetryRateLimiter} with the specified number of permits per second.
     *
     * @param permitsPerSecond the number of retry permits allowed per second; must be positive
     */
    static RetryLimiter ofRateLimiter(double permitsPerSecond) {
        return new RetryRateLimiter(permitsPerSecond);
    }

    /**
     * Creates a new {@link GrpcRetryLimiter} with the specified parameters.
     *
     * @param maxTokens the initial token count (must be positive)
     * @param tokenRatio the number of tokens a successful request increments (must be positive)
     */
    static RetryLimiter ofGrpc(float maxTokens, float tokenRatio) {
        return new GrpcRetryLimiter(maxTokens, tokenRatio);
    }

    /**
     * Creates a new {@link GrpcRetryLimiter} with the specified parameters.
     *
     * @param maxTokens the initial token count (must be positive)
     * @param tokenRatio the number of tokens a successful request increments (must be positive)
     * @param threshold the minimum token count required to allow a retry (must be positive and less than or
     *     equal to {@code maxTokens})
     * @param retryableStatuses the collection of gRPC status codes that are considered retryable(must not be
     *     null or empty)
     */
    static RetryLimiter ofGrpc(float maxTokens, float tokenRatio, float threshold,
                               Collection<Integer> retryableStatuses) {
        return new GrpcRetryLimiter(maxTokens, tokenRatio, threshold, retryableStatuses);
    }

    /**
     * Determines whether the request should be retried.
     * This method is not invoked:
     * <ul>
     *   <li>if the maximum number of attempts has been reached,</li>
     *   <li>if the request has been cancelled,</li>
     *   <li>if the request is the first attempt,</li>
     *   <li>if the retry rule has decided not to retry.</li>
     * </ul>
     *
     * @param ctx the request context
     * @param numAttemptsSoFar the number of attempts made so far
     * @return {@code true} if the request should be retried
     */
    boolean shouldRetry(ClientRequestContext ctx, int numAttemptsSoFar);

    /**
     * Invoked when an attempt completes regardless of the state and if it is a retry or not.
     * You can use this function to implement your retry limiting algorithm.
     *
     * @param ctx full request context that also includes response information
     * @param requestLog reduced context with request and response information
     * @param numAttemptsSoFar the number of attempts made so far, including the current one
     */
    default void onCompletedAttempt(ClientRequestContext ctx, RequestLog requestLog, int numAttemptsSoFar) {}
}
