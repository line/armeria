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

import java.time.Duration;

import com.linecorp.armeria.common.HttpResponse;

/**
 * Builds a new {@link RetryingClient} or its decorator function.
 *
 * @deprecated Use {@link RetryingClientBuilder}.
 */
@Deprecated
public class RetryingHttpClientBuilder extends RetryingClientBuilder {

    /**
     * Creates a new builder with the specified {@link RetryStrategy}.
     *
     * @deprecated Use {@link RetryingClient#builder(RetryStrategy)}.
     */
    @Deprecated
    public RetryingHttpClientBuilder(RetryStrategy retryStrategy) {
        super(retryStrategy);
    }

    /**
     * Creates a new builder with the specified {@link RetryStrategyWithContent}.
     *
     * @deprecated Use {@link RetryingClient#builder(RetryStrategyWithContent)}.
     */
    @Deprecated
    public RetryingHttpClientBuilder(RetryStrategyWithContent<HttpResponse> retryStrategyWithContent) {
        super(retryStrategyWithContent);
    }

    @Override
    public RetryingHttpClientBuilder useRetryAfter(boolean useRetryAfter) {
        return (RetryingHttpClientBuilder) super.useRetryAfter(useRetryAfter);
    }

    @Override
    public RetryingHttpClientBuilder contentPreviewLength(int contentPreviewLength) {
        return (RetryingHttpClientBuilder) super.contentPreviewLength(contentPreviewLength);
    }

    @Override
    public RetryingHttpClientBuilder maxTotalAttempts(int maxTotalAttempts) {
        return (RetryingHttpClientBuilder) super.maxTotalAttempts(maxTotalAttempts);
    }

    @Override
    public RetryingHttpClientBuilder responseTimeoutMillisForEachAttempt(
            long responseTimeoutMillisForEachAttempt) {
        return (RetryingHttpClientBuilder) super.responseTimeoutMillisForEachAttempt(
                responseTimeoutMillisForEachAttempt);
    }

    @Override
    public RetryingHttpClientBuilder responseTimeoutForEachAttempt(Duration responseTimeoutForEachAttempt) {
        return (RetryingHttpClientBuilder) super.responseTimeoutForEachAttempt(responseTimeoutForEachAttempt);
    }
}
