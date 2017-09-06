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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Builds a new {@link RetryingClient} or its decorator function.
 *
 * @param <T> the type of {@link RetryingClientBuilder}
 * @param <U> the type of the {@link Client} that this builder builds or decorates
 * @param <I> the type of outgoing {@link Request} of the {@link Client}
 * @param <O> the type of incoming {@link Response} of the {@link Client}
 */
public abstract class RetryingClientBuilder<
        T extends RetryingClientBuilder<T, U, I, O>, U extends RetryingClient<I, O>,
        I extends Request, O extends Response> {

    private static final BackoffSpec DEFAULT_BACKOFF_SPEC = BackoffSpec.parse(Flags.defaultBackoffSpec());

    final RetryStrategy<I, O> retryStrategy;
    int defaultMaxAttempts = DEFAULT_BACKOFF_SPEC.maxAttempts;
    long responseTimeoutMillisForEachAttempt = Flags.defaultResponseTimeoutMillis();

    /**
     * Creates a new builder with the specified retry strategy.
     */
    protected RetryingClientBuilder(RetryStrategy<I, O> retryStrategy) {
        this.retryStrategy = requireNonNull(retryStrategy, "retryStrategy");
    }

    @SuppressWarnings("unchecked")
    final T self() {
        return (T) this;
    }

    /**
     * Sets the {@code defaultMaxAttempts}. The value will be set by the default value in
     * {@link Flags#DEFAULT_BACKOFF_SPEC}, if the client dose not specify.
     *
     * @return {@link T} to support method chaining.
     */
    public T defaultMaxAttempts(int defaultMaxAttempts) {
        checkArgument(defaultMaxAttempts > 0,
                      "defaultMaxAttempts: %s (expected: > 0)", defaultMaxAttempts);
        this.defaultMaxAttempts = defaultMaxAttempts;
        return self();
    }

    /**
     * Sets the response timeout for each attempt in milliseconds. When requests in {@link RetryingClient}
     * are made, corresponding responses are timed out by this value. {@code 0} disables the timeout.
     * It will be set by the default value in {@link Flags#defaultResponseTimeoutMillis()}, if the client
     * dose not specify.
     *
     * @return {@link T} to support method chaining.
     */
    public T responseTimeoutMillisForEachAttempt(long responseTimeoutMillisForEachAttempt) {
        checkArgument(responseTimeoutMillisForEachAttempt >= 0,
                      "responseTimeoutMillisForEachAttempt: %s (expected: >= 0)",
                      responseTimeoutMillisForEachAttempt);
        this.responseTimeoutMillisForEachAttempt = responseTimeoutMillisForEachAttempt;
        return self();
    }

    /**
     * Sets the response timeout for each attempt. When requests in {@link RetryingClient} are made,
     * corresponding responses are timed out by this value. {@code 0} disables the timeout.
     *
     * @return {@link T} to support method chaining.
     */
    public T responseTimeoutForEachAttempt(Duration responseTimeoutForEachAttempt) {
        checkArgument(
                !requireNonNull(responseTimeoutForEachAttempt, "responseTimeoutForEachAttempt").isNegative(),
                "responseTimeoutForEachAttempt: %s (expected: >= 0)", responseTimeoutForEachAttempt);
        return responseTimeoutMillisForEachAttempt(responseTimeoutForEachAttempt.toMillis());
    }

    /**
     * Returns a newly-created {@link RetryingClient} based on the properties of this builder.
     */
    abstract U build(Client<I, O> delegate);

    /**
     * Returns a newly-created decorator that decorates a {@link Client} with a new {@link RetryingClient}
     * based on the properties of this builder.
     */
    abstract Function<Client<I, O>, U> newDecorator();

    @Override
    public String toString() {
        return toStringHelper().toString();
    }

    ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this)
                          .add("retryStrategy", retryStrategy)
                          .add("defaultMaxAttempts", defaultMaxAttempts)
                          .add("responseTimeoutMillisForEachAttempt", responseTimeoutMillisForEachAttempt);
    }
}
