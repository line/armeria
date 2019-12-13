/*
 * Copyright 2017 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpResponse;

/**
 * An {@link HttpClient} decorator that handles failures of an invocation and retries HTTP requests.
 *
 * @deprecated Use {@link RetryingClient}.
 */
@Deprecated
public final class RetryingHttpClient extends RetryingClient {

    /**
     * Returns a new {@link RetryingHttpClientBuilder} with the specified {@link RetryStrategy}.
     *
     * @deprecated Use {@link RetryingClient#builder(RetryStrategy)}.
     */
    @Deprecated
    public static RetryingHttpClientBuilder builder(RetryStrategy retryStrategy) {
        return new RetryingHttpClientBuilder(retryStrategy);
    }

    /**
     * Returns a new {@link RetryingHttpClientBuilder} with the specified {@link RetryStrategyWithContent}.
     *
     * @deprecated Use {@link RetryingHttpClient#builder(RetryStrategyWithContent)}.
     */
    @Deprecated
    public static RetryingHttpClientBuilder builder(
            RetryStrategyWithContent<HttpResponse> retryStrategyWithContent) {
        return new RetryingHttpClientBuilder(retryStrategyWithContent);
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryStrategy the retry strategy
     * @deprecated Use {@link RetryingClient#newDecorator(RetryStrategy)}.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryStrategy retryStrategy) {
        return builder(retryStrategy).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryStrategy the retry strategy
     * @param maxTotalAttempts the maximum number of total attempts
     * @deprecated Use {@link RetryingClient#newDecorator(RetryStrategy, int)}.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryStrategy retryStrategy, int maxTotalAttempts) {
        return builder(retryStrategy).maxTotalAttempts(maxTotalAttempts)
                                     .newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryStrategy the retry strategy
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     * @deprecated Use {@link RetryingClient#newDecorator(RetryStrategy, int, long)}.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryStrategy retryStrategy,
                 int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return builder(retryStrategy).maxTotalAttempts(maxTotalAttempts)
                                     .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                                     .newDecorator();
    }

    RetryingHttpClient(HttpClient delegate,
                       RetryStrategy retryStrategy, int totalMaxAttempts,
                       long responseTimeoutMillisForEachAttempt, boolean useRetryAfter) {
        super(delegate, retryStrategy, totalMaxAttempts, responseTimeoutMillisForEachAttempt, useRetryAfter);
    }
}
