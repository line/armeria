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

import static java.util.Objects.requireNonNull;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * A skeletal builder implementation for {@link Backoff}.
 */
abstract class AbstractBackoffBuilder<SELF extends AbstractBackoffBuilder<SELF>> {
    @Nullable
    private Double minJitterRate;
    @Nullable
    private Double maxJitterRate;
    @Nullable
    private Integer maxAttempts;
    @Nullable
    private Supplier<Random> randomSupplier;

    @SuppressWarnings("unchecked")
    private SELF self() {
        return (SELF) this;
    }

    /**
     * Sets the minimum and maximum jitter rates to apply to the delay.
     */
    public final SELF jitter(double minJitterRate, double maxJitterRate) {
        this.minJitterRate = minJitterRate;
        this.maxJitterRate = maxJitterRate;
        return self();
    }

    /**
     * Sets the minimum and maximum jitter rates to apply to the delay, as well as a
     * custom {@link Random} supplier for generating the jitter.
     */
    public final SELF jitter(double minJitterRate, double maxJitterRate, Supplier<Random> randomSupplier) {
        requireNonNull(randomSupplier, "randomSupplier");
        this.minJitterRate = minJitterRate;
        this.maxJitterRate = maxJitterRate;
        this.randomSupplier = randomSupplier;
        return self();
    }

    /**
     * Sets the maximum number of attempts.
     */
    public final SELF maxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        return self();
    }

    abstract Backoff doBuild();

    /**
     * Builds and returns {@link Backoff} instance with configured properties.
     */
    public final Backoff build() {
        Backoff backoff = doBuild();
        if (minJitterRate != null && maxJitterRate != null) {
            Supplier<Random> randomSupplier = this.randomSupplier;
            if (randomSupplier == null) {
                randomSupplier = ThreadLocalRandom::current;
            }
            backoff = new JitterAddingBackoff(backoff, minJitterRate, maxJitterRate, randomSupplier);
        }
        if (maxAttempts != null) {
            backoff = new AttemptLimitingBackoff(backoff, maxAttempts);
        }
        return backoff;
    }
}
