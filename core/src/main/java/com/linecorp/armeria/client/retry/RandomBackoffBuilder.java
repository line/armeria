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
import static java.util.Objects.requireNonNull;

import java.util.Random;
import java.util.function.Supplier;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for creating instances of {@link RandomBackoff}.
 *
 * <p>This builder allows you to configure the delay duration for a random backoff strategy.
 * You can specify the minimum delay, maximum delay in milliseconds and random supplier
 * then create a {@link RandomBackoff} instance.</p>
 *
 * <p>Example usage:</p>
 *
 * <pre>
 * {@code
 * RandomBackOff backoff = new RandomBackoffBuilder()
 *     .minDelayMillis(1000)
 *     .maxDelayMillis(3000)
 *     .build();
 * }
 * </pre>
 *
 * @see RandomBackoff
 */
@UnstableApi
public final class RandomBackoffBuilder {
    private long minDelayMillis;
    private long maxDelayMillis;
    private Supplier<Random> randomSupplier = Random::new;

    RandomBackoffBuilder() {}

    /**
     * Builds and returns a newly created {@link RandomBackoff} instance with the properties
     * specified in this builder.
     *
     * <p>If some properties are not set, they will default to the following values:</p>
     * <ul>
     *     <li><b>minDelayMillis</b>: 0</li>
     *     <li><b>maxDelayMillis</b>: 0</li>
     *     <li><b>randomSupplier</b>: {@code Random::new}</li>
     * </ul>
     *
     * <p>All provided or defaulted properties will be applied to the {@link RandomBackoff} instance
     * created by this method.</p>
     *
     * @return a newly created {@link RandomBackoff} instance with the configured or default values
     */
    public Backoff build() {
        return new RandomBackoff(minDelayMillis, maxDelayMillis, randomSupplier);
    }

    /**
     * Sets the minimum delay, in milliseconds, for the {@link RandomBackoff}.
     *
     * <p>This value represents the minimum time the backoff will delay before retrying an operation.</p>
     *
     * @param minDelayMillis the minimum delay in milliseconds
     * @return this {@code RandomBackoffBuilder} instance for method chaining
     */
    public RandomBackoffBuilder minDelayMillis(long minDelayMillis) {
        checkArgument(minDelayMillis >= 0, "minDelayMillis: %s (expected: >= 0)", minDelayMillis);
        checkArgument(minDelayMillis <= maxDelayMillis, "minDelayMillis: %s (expected: <= %s)",
                      minDelayMillis, maxDelayMillis);
        this.minDelayMillis = minDelayMillis;
        return this;
    }

    /**
     * Sets the maximum delay, in milliseconds, for the {@link RandomBackoff}.
     *
     * <p>This value represents the maximum time the backoff will delay before retrying an operation.</p>
     *
     * @param maxDelayMillis the maximum delay in milliseconds
     * @return this {@code RandomBackoffBuilder} instance for method chaining
     */
    public RandomBackoffBuilder maxDelayMillis(long maxDelayMillis) {
        checkArgument(maxDelayMillis >= 0, "maxDelayMillis: %s (expected: >= 0)", maxDelayMillis);
        checkArgument(minDelayMillis <= maxDelayMillis, "maxDelayMillis: %s (expected: >= %s)",
                      maxDelayMillis, minDelayMillis);
        this.maxDelayMillis = maxDelayMillis;
        return this;
    }

    /**
     * Sets the {@link Supplier} that provides instances of {@link Random} for the {@link RandomBackoff}.
     *
     * <p>This supplier will be used to generate random values when determining the
     * backoff delay between retries.</p>
     *
     * @param randomSupplier a {@link Supplier} that provides {@link Random} instances
     * @return this {@code RandomBackoffBuilder} instance for method chaining
     * @throws NullPointerException if {@code randomSupplier} is {@code null}
     */
    public RandomBackoffBuilder randomSupplier(Supplier<Random> randomSupplier) {
        requireNonNull(randomSupplier, "randomSupplier");
        this.randomSupplier = randomSupplier;
        return this;
    }
}
