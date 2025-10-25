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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.retry.limiter.RetryLimiter;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;

/**
 * Executes {@link RetryLimiter} operations with proper exception handling.
 * <p>
 * This class provides static utility methods to safely execute retry limiter operations.
 * It handles null limiters gracefully and catches any exceptions thrown by the limiter
 * to prevent them from affecting the retry logic.
 * </p>
 * <p>
 * When a limiter throws an exception, it is logged as an error and the operation
 * defaults to allowing the retry to proceed (returning {@code true} for {@link #shouldRetry}
 * and doing nothing for {@link #onCompletedAttempt}).
 * </p>
 */
final class RetryLimiterExecutor {

    private RetryLimiterExecutor() {}

    private static final Logger logger = LoggerFactory.getLogger(RetryLimiterExecutor.class);

    /**
     * Determines whether a retry should be attempted based on the provided limiter.
     * <p>
     * This method safely executes the limiter's {@link RetryLimiter#shouldRetry} method.
     * If the limiter is null, this method returns {@code true} to allow retries.
     * If the limiter throws an exception, it is logged and {@code true} is returned
     * to ensure retries can still proceed.
     * </p>
     *
     * @param limiter the retry limiter to consult, or {@code null} if no limiter is configured
     * @param ctx the client request context
     * @param numAttemptsSoFar the number of attempts made so far (0-based)
     * @return {@code true} if a retry should be attempted, {@code false} otherwise.
     *         Returns {@code true} if the limiter is null or throws an exception.
     */
    public static boolean shouldRetry(@Nullable RetryLimiter limiter, ClientRequestContext ctx,
                                      int numAttemptsSoFar) {
        try {
            if (limiter != null) {
                return limiter.shouldRetry(ctx, numAttemptsSoFar);
            } else {
                return true;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute RetryLimiter.shouldRetry: limiter={}, attempts={}", limiter,
                         numAttemptsSoFar, t);
            return true;
        }
    }

    /**
     * Notifies the limiter that an attempt has been completed.
     * <p>
     * This method safely executes the limiter's {@link RetryLimiter#onCompletedAttempt} method.
     * If the limiter is null, this method does nothing.
     * If the limiter throws an exception, it is logged but does not affect the retry flow.
     * </p>
     *
     * @param limiter the retry limiter to notify, or {@code null} if no limiter is configured
     * @param ctx the client request context
     * @param requestLog the request log containing details about the completed attempt
     * @param numAttemptsSoFar the number of attempts made so far (0-based)
     */
    public static void onCompletedAttempt(@Nullable RetryLimiter limiter, ClientRequestContext ctx,
                                          RequestLog requestLog, int numAttemptsSoFar) {
        try {
            if (limiter != null) {
                limiter.onCompletedAttempt(ctx, requestLog, numAttemptsSoFar);
            }
        } catch (Throwable t) {
            logger.error("Failed to execute RetryLimiter.onCompletedAttempt: limiter={}, attempts={}", limiter,
                         numAttemptsSoFar, t);
        }
    }
}
