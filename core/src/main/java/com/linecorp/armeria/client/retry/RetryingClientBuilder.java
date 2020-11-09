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
import java.util.function.Function;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpResponse;

/**
 * Builds a new {@link RetryingClient} or its decorator function.
 */
public final class RetryingClientBuilder extends AbstractRetryingClientBuilder<HttpResponse> {

    private boolean useRetryAfter;

    /**
     * Creates a new builder with the specified {@link RetryRule}.
     */
    RetryingClientBuilder(RetryRule retryRule) {
        super(retryRule);
    }

    /**
     * Creates a new builder with the specified {@link RetryRuleWithContent}.
     */
    RetryingClientBuilder(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        super(retryRuleWithContent);
    }

    /**
     * Creates a new builder with the specified {@link RetryRuleWithContent} and maxContentLength.
     */
    RetryingClientBuilder(RetryRuleWithContent<HttpResponse> retryRuleWithContent, int maxContentLength) {
        super(retryRuleWithContent);
        maxContentLength(maxContentLength);
    }

    /**
     * Creates a new builder with the specified {@link RetryConfigMapping}.
     */
    RetryingClientBuilder(RetryConfigMapping<HttpResponse> mapping) {
        super(mapping);
    }

    /**
     * Whether retry should be attempted according to the {@code retryHeader} from the server or not.
     * The web server may request a client to retry after specific time with {@code retryAfter} header.
     * If you want to follow the direction from the server not by your {@link Backoff}, invoke this method
     * with the {@code useRetryAfter} with {@code true} to request after the specified delay.
     *
     * @param useRetryAfter {@code true} if you want to retry after using the {@code retryAfter} header.
     *                      {@code false} otherwise
     * @return {@link RetryingClientBuilder} to support method chaining
     */
    public RetryingClientBuilder useRetryAfter(boolean useRetryAfter) {
        this.useRetryAfter = useRetryAfter;
        return this;
    }

    /**
     * Returns a newly-created {@link RetryingClient} based on the properties of this builder.
     */
    public RetryingClient build(HttpClient delegate) {
        return new RetryingClient(delegate, mapping(), retryConfig(), useRetryAfter);
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpClient} with a new
     * {@link RetryingClient} based on the properties of this builder.
     */
    public Function<? super HttpClient, RetryingClient> newDecorator() {
        return this::build;
    }

    @Override
    public String toString() {
        return toStringHelper().add("useRetryAfter", useRetryAfter).toString();
    }

    // Methods that were overridden to change the return type.

    @Override
    public RetryingClientBuilder maxTotalAttempts(int maxTotalAttempts) {
        return (RetryingClientBuilder) super.maxTotalAttempts(maxTotalAttempts);
    }

    @Override
    public RetryingClientBuilder responseTimeoutMillisForEachAttempt(
            long responseTimeoutMillisForEachAttempt) {
        return (RetryingClientBuilder)
                super.responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt);
    }

    @Override
    public RetryingClientBuilder responseTimeoutForEachAttempt(Duration responseTimeoutForEachAttempt) {
        return (RetryingClientBuilder) super.responseTimeoutForEachAttempt(responseTimeoutForEachAttempt);
    }
}
