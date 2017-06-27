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

import static java.util.Objects.requireNonNull;

import java.util.function.Supplier;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * A {@link Client} decorator that handles failures of remote invocation and retries requests.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class RetryingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private final Supplier<? extends Backoff> backoffSupplier;
    private final RetryStrategy<I, O> retryStrategy;
    private final int defaultMaxAttempts;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    protected RetryingClient(Client<I, O> delegate, RetryStrategy<I, O> retryStrategy,
                             Supplier<? extends Backoff> backoffSupplier, int defaultMaxAttempts) {
        super(delegate);
        this.retryStrategy = requireNonNull(retryStrategy, "retryStrategy");
        this.backoffSupplier = requireNonNull(backoffSupplier, "backoffSupplier");
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    /**
     * Creates a new {@link Backoff} instance.
     * If the created instance does not have {@link AttemptLimitingBackoff}, it will be wrapped with
     * the instance of {@link AttemptLimitingBackoff}.
     * @return the {@link Backoff} which is wrapped by {@link AttemptLimitingBackoff} if it doesn't have
     */
    protected Backoff newBackoff() {
        Backoff backoff = backoffSupplier.get();
        if (!backoff.as(AttemptLimitingBackoff.class).isPresent()) {
            backoff = backoff.withMaxAttempts(defaultMaxAttempts);
        }
        return backoff;
    }

    protected RetryStrategy<I, O> retryStrategy() {
        return retryStrategy;
    }
}
