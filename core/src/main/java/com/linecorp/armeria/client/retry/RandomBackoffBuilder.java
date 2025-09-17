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
 * A builder for creating instances of Random {@link Backoff}.
 *
 * <p>This builder allows you to configure a random backoff strategy by specifying
 * a minimum delay, maximum delay in milliseconds and random supplier.
 * <p>Example usage:</p>
 *
 * <pre>
 * {@code
 * BackOff backoff = Backoff.builderForRandom()
 *     .minDelayMillis(200)
 *     .maxDelayMillis(10000)
 *     .build();
 * }
 * </pre>
 */
@UnstableApi
public final class RandomBackoffBuilder extends AbstractBackoffBuilder<RandomBackoffBuilder> {

    static final long DEFAULT_MIN_DELAY_MILLIS = 200;
    static final long DEFAULT_MAX_DELAY_MILLIS = 10000;

    private long minDelayMillis = 200;
    private long maxDelayMillis = 10000;
    private Supplier<Random> randomSupplier = Random::new;

    RandomBackoffBuilder() {}

    /**
     * Sets the minimum delay, in milliseconds, for the Random {@link Backoff}.
     *
     * <p>This value represents the minimum time the backoff will delay before retrying an operation.</p>
     *
     * @param minDelayMillis the minimum delay in milliseconds
     * @return this {@code RandomBackoffBuilder} instance for method chaining
     */
    public RandomBackoffBuilder minDelayMillis(long minDelayMillis) {
        checkArgument(minDelayMillis >= 0, "minDelayMillis: %s (expected: >= 0)", minDelayMillis);
        this.minDelayMillis = minDelayMillis;
        return this;
    }

    /**
     * Sets the maximum delay, in milliseconds, for the Random {@link Backoff}.
     *
     * <p>This value represents the maximum time the backoff will delay before retrying an operation.</p>
     *
     * @param maxDelayMillis the maximum delay in milliseconds
     * @return this {@code RandomBackoffBuilder} instance for method chaining
     */
    public RandomBackoffBuilder maxDelayMillis(long maxDelayMillis) {
        checkArgument(maxDelayMillis >= 0, "maxDelayMillis: %s (expected: >= 0)", maxDelayMillis);
        this.maxDelayMillis = maxDelayMillis;
        return this;
    }

    /**
     * Sets the {@link Supplier} that provides instances of {@link Random} for the Random {@link Backoff}.
     *
     * <p>This supplier will be used to generate random values when determining the
     * backoff delay between retries.</p>
     *
     * @param randomSupplier a {@link Supplier} that provides {@link Random} instances
     * @return this {@code RandomBackoffBuilder} instance for method chaining
     */
    public RandomBackoffBuilder randomSupplier(Supplier<Random> randomSupplier) {
        requireNonNull(randomSupplier, "randomSupplier");
        this.randomSupplier = randomSupplier;
        return this;
    }

    @Override
    Backoff doBuild() {
        return new RandomBackoff(minDelayMillis, maxDelayMillis, randomSupplier);
    }
}
