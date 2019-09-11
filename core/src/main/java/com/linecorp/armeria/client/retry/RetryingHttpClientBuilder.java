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

import com.google.common.base.MoreObjects.ToStringHelper;
import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

import java.time.Duration;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/**
 * Builds a new {@link RetryingHttpClient} or its decorator function.
 */
public class RetryingHttpClientBuilder
        extends RetryingClientBuilder<RetryingHttpClient, HttpRequest, HttpResponse> {

    private static final int DEFAULT_CONTENT_PREVIEW_LENGTH = Integer.MAX_VALUE;

    private boolean useRetryAfter;

    private int contentPreviewLength = DEFAULT_CONTENT_PREVIEW_LENGTH;

    private final boolean needsContentInStrategy;

    /**
     * Creates a new builder with the specified {@link RetryStrategy}.
     *
     * @deprecated Use {@link RetryingHttpClient#builder(RetryStrategy)}.
     */
    @Deprecated
    public RetryingHttpClientBuilder(RetryStrategy retryStrategy) {
        super(retryStrategy);
        needsContentInStrategy = false;
    }

    /**
     * Creates a new builder with the specified {@link RetryStrategyWithContent}.
     *
     * @deprecated Use {@link RetryingHttpClient#builder(RetryStrategyWithContent)}.
     */
    @Deprecated
    public RetryingHttpClientBuilder(RetryStrategyWithContent<HttpResponse> retryStrategyWithContent) {
        super(retryStrategyWithContent);
        needsContentInStrategy = true;
    }

    /**
     * Whether retry should be attempted according to the {@code retryHeader} from the server or not.
     * The web server may request a client to retry after specific time with {@code retryAfter} header.
     * If you want to follow the direction from the server not by your {@link Backoff}, invoke this method
     * with the {@code useRetryAfter} with {@code true} to request after the specified delay.
     *
     * @param useRetryAfter {@code true} if you want to retry after using the {@code retryAfter} header.
     *                      {@code false} otherwise
     * @return {@link RetryingHttpClientBuilder} to support method chaining
     */
    public RetryingHttpClientBuilder useRetryAfter(boolean useRetryAfter) {
        this.useRetryAfter = useRetryAfter;
        return this;
    }

    /**
     * Sets the length of content required to determine whether to retry or not. If the total length of content
     * exceeds this length and there's no retry condition matched, it will hand over the stream to the client.
     * Note that this property is useful only if you specified {@link RetryStrategyWithContent} when calling
     * this builder's constructor. The default value of this property is
     * {@value #DEFAULT_CONTENT_PREVIEW_LENGTH}.
     *
     * @param contentPreviewLength the content length to preview. {@code 0} does not disable the length limit.
     *
     * @return {@link RetryingHttpClientBuilder} to support method chaining
     *
     * @throws IllegalStateException if this builder is created with a {@link RetryStrategy} rather than
     *                               {@link RetryStrategyWithContent}
     * @throws IllegalArgumentException if the specified {@code contentPreviewLength} is equal to or
     *                                  less than {@code 0}
     */
    public RetryingHttpClientBuilder contentPreviewLength(int contentPreviewLength) {
        checkState(needsContentInStrategy, "cannot set contentPreviewLength when RetryStrategy is used; " +
                                           "Use RetryStrategyWithContent to enable this feature.");
        checkArgument(contentPreviewLength > 0,
                      "contentPreviewLength: %s (expected: > 0)", contentPreviewLength);
        this.contentPreviewLength = contentPreviewLength;
        return this;
    }

    /**
     * Returns a newly-created {@link RetryingHttpClient} based on the properties of this builder.
     */
    @Override
    public RetryingHttpClient build(Client<HttpRequest, HttpResponse> delegate) {
        if (needsContentInStrategy) {
            return new RetryingHttpClient(delegate, retryStrategyWithContent(), maxTotalAttempts(),
                                          responseTimeoutMillisForEachAttempt(), useRetryAfter,
                                          contentPreviewLength);
        }

        return new RetryingHttpClient(delegate, retryStrategy(), maxTotalAttempts(),
                                      responseTimeoutMillisForEachAttempt(), useRetryAfter);
    }

    /**
     * Returns a newly-created decorator that decorates a {@link Client} with a new {@link RetryingHttpClient}
     * based on the properties of this builder.
     */
    @Override
    public Function<Client<HttpRequest, HttpResponse>, RetryingHttpClient> newDecorator() {
        return this::build;
    }

    @Override
    public String toString() {
        final ToStringHelper stringHelper = toStringHelper().add("useRetryAfter", this.useRetryAfter);
        if (needsContentInStrategy) {
            stringHelper.add("contentPreviewLength", contentPreviewLength);
        }
        return stringHelper.toString();
    }

    // Methods that were overridden to change the return type.

    @Override
    public RetryingHttpClientBuilder maxTotalAttempts(int maxTotalAttempts) {
        return (RetryingHttpClientBuilder) super.maxTotalAttempts(maxTotalAttempts);
    }

    @Override
    public RetryingHttpClientBuilder responseTimeoutMillisForEachAttempt(
            long responseTimeoutMillisForEachAttempt) {
        return (RetryingHttpClientBuilder)
                super.responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt);
    }

    @Override
    public RetryingHttpClientBuilder responseTimeoutForEachAttempt(Duration responseTimeoutForEachAttempt) {
        return (RetryingHttpClientBuilder) super.responseTimeoutForEachAttempt(responseTimeoutForEachAttempt);
    }
}
