/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.Response;

/**
 * Determines whether a failed request should be retried using the content of a {@link Response}.
 * If you just need the {@link HttpHeaders} to make a decision, use {@link RetryStrategy} for efficiency.
 *
 * @param <T> the response type
 */
@FunctionalInterface
public interface RetryStrategyWithContent<T extends Response> {

    /**
     * Returns a {@link CompletionStage} that contains {@link Backoff} which will be used for retry.
     * If the condition does not match, this will return {@code null} to stop retry attempt.
     * Note that {@link ResponseTimeoutException} is not retriable for the whole retry,
     * but only for each attempt.
     *
     * @param ctx the {@link ClientRequestContext} of this request
     * @param response the {@link Response} from the server
     *
     * @see RetryingClientBuilder#responseTimeoutMillisForEachAttempt(long)
     *
     * @see <a href="https://line.github.io/armeria/advanced-retry.html#per-attempt-timeout">Per-attempt
     *      timeout</a>
     */
    CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, T response);
}
