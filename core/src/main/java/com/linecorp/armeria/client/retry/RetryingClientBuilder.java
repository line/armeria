/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientOptions;
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

    final RetryStrategy<I, O> retryStrategy;
    // TODO(minwoox) remove hardcoded paramters and add them to Flags with a new parser
    Supplier<? extends Backoff> backoffSupplier = () -> Backoff
            .exponential(Flags.defaultExponentialBackoffInitialDelayMillis(),
                         ClientOptions.DEFAULT.defaultResponseTimeoutMillis())
            .withJitter(0.3)
            .withMaxAttempts(Flags.defaultBackoffMaxAttempts());
    int defaultMaxAttempts = Flags.defaultBackoffMaxAttempts();

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
     * Sets the {@link Supplier} that provides a {@link Backoff}. The {@link Backoff} will be used to
     * calculate next delay to retry when the {@link RetryStrategy#shouldRetry(Request, Response)}
     * returns {@code true}.
     *
     * @return {@link T} to support method chaining.
     */
    public T backoffSupplier(Supplier<? extends Backoff> backoffSupplier) {
        this.backoffSupplier = requireNonNull(backoffSupplier, "backoffSupplier");
        return self();
    }

    /**
     * Sets the {@code defaultMaxAttempts}. When a client sets the {@link Backoff} and does not invoke the
     * {@link Backoff#withMaxAttempts(int)}, the client could retry infinitely in certain circumstance.
     * This would prevent that situation. The value will be set by the
     * {@link Flags#DEFAULT_BACKOFF_MAX_ATTEMPTS}, if the client dose not specify.
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
                          .add("backoff", backoffSupplier.get())
                          .add("defaultMaxAttempts", defaultMaxAttempts);
    }
}
