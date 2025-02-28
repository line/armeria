/*
 * Copyright 2024 LY Corporation
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

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for creating instances of Fibonacci {@link Backoff}.
 *
 * <p>This builder allows you to configure a Fibonacci backoff strategy by specifying
 * an initial delay and a maximum delay in milliseconds. The Fibonacci backoff strategy
 * increases the delay between retries according to the Fibonacci sequence, while respecting
 * the configured maximum delay.</p>
 *
 * <p>Example usage:</p>
 *
 * <pre>
 * {@code
 * Backoff backoff = Backoff.builderForFibonacci()
 *     .initialDelayMillis(200)
 *     .maxDelayMillis(10000)
 *     .build();
 * }
 * </pre>
 */
@UnstableApi
public final class FibonacciBackoffBuilder extends AbstractBackoffBuilder<FibonacciBackoffBuilder> {

    static final long DEFAULT_INITIAL_DELAY_MILLIS = 200;
    static final long DEFAULT_MAX_DELAY_MILLIS = 10000;

    private long initialDelayMillis = 200;
    private long maxDelayMillis = 10000;

    FibonacciBackoffBuilder() {}

    /**
     * Sets the initial delay in milliseconds for the Fibonacci {@link Backoff}.
     *
     * <p>The initial delay is the base value from which the Fibonacci sequence will start,
     * and it determines the delay before the first retry.</p>
     *
     * @param initialDelayMillis the initial delay in milliseconds
     */
    public FibonacciBackoffBuilder initialDelayMillis(long initialDelayMillis) {
        checkArgument(initialDelayMillis >= 0,
                      "initialDelayMillis: %s (expected: >= 0)", initialDelayMillis);

        this.initialDelayMillis = initialDelayMillis;
        return this;
    }

    /**
     * Sets the maximum delay in milliseconds for the Fibonacci {@link Backoff}.
     *
     * <p>The maximum delay sets an upper limit to the delays generated by the Fibonacci
     * sequence. Once the delays reach this value, they will not increase further.</p>
     *
     * @param maxDelayMillis the maximum delay in milliseconds
     */
    public FibonacciBackoffBuilder maxDelayMillis(long maxDelayMillis) {
        checkArgument(maxDelayMillis >= 0,
                      "maxDelayMillis: %s (expected: >= 0)", maxDelayMillis);
        this.maxDelayMillis = maxDelayMillis;
        return this;
    }

    @Override
    Backoff doBuild() {
        return new FibonacciBackoff(initialDelayMillis, maxDelayMillis);
    }
}
