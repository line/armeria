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

import com.linecorp.armeria.client.ClientRequestContext;

/**
 * Provides a way to limit the number of retries.
 * This may be useful for situations where prolonged service degradation may trigger
 * an explosion of retries, which may further exacerbate the overall system.
 * <pre>{@code
 * var rule = RetryRule.of(RetryRule.builder()
 *                     .onStatus(SERVICE_UNAVAILABLE)
 *                     // increment permits
 *                     .build(RetryDecision.retry(Backoff.ofDefault(), 1)),
 *            RetryRule.builder()
 *                     .onStatus(OK)
 *                     // decrement permits
 *                     .build(RetryDecision.retry(Backoff.ofDefault(), -1)));
 * var config = RetryConfig.builder(rule)
 *                         // max 5 concurrent retry request
 *                         .retryLimiter(RetryLimiter.concurrencyLimiting(5))
 *                         .build();
 * var decorator = RetryingClient.newDecorator(config)
 * }</pre>
 */
public interface RetryLimiter {

    /**
     * Returns a {@link RetryLimiter} which limits the number of concurrent retry requests.
     * This limiter does not consider {@link RetryDecision#permits()} when limiting retries.
     */
    static RetryLimiter concurrencyLimiting(long maxRequests) {
        return new ConcurrencyBasedRetryLimiter(maxRequests);
    }

    /**
     * A token based {@link RetryLimiter} based on gRPC's retry throttling algorithm.
     * Given {@code maxTokens} amount of tokens, each positive {@link RetryDecision#permits()} will
     * increment available tokens by {@code tokenRatio}, and each negative {@link RetryDecision#permits()} will
     * decrement available tokens by {@code 1}. Retries will be limited if half or less of
     * {code maxTokens} remains. Only the sign of the {@link RetryDecision#permits()} is considered.
     * Refer to
     * <a href="https://github.com/grpc/proposal/blob/master/A6-client-retries.md#throttling-retry-attempts-and-hedged-rpcs">gRPC's documentation</a>
     * for more details.
     */
    static RetryLimiter tokenBased(float maxTokens, float tokenRatio) {
        return new TokenBasedRetryLimiter(maxTokens, tokenRatio);
    }

    /**
     * A callback method which is invoked before each retry (excluding the first request).
     * The returned value determines whether a retry request should be executed, or whether a
     * {@link RetryLimitedException} will be thrown.
     *
     * @param ctx the {@link ClientRequestContext} of the current request which will be executed
     * @return {@code true} if the request should be executed
     */
    boolean shouldRetry(ClientRequestContext ctx);

    /**
     * A callback method which is invoked after a {@link RetryDecision} has been determined.
     *
     * @param ctx the {@link ClientRequestContext} of the request used to derive the {@link RetryDecision}
     * @param decision the computed {@link RetryDecision}
     */
    void handleDecision(ClientRequestContext ctx, RetryDecision decision);
}
