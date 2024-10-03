package com.linecorp.armeria.client.retry;

import java.util.Random;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

public class RandomBackoffBuilder {
    private long minDelayMillis;
    private long maxDelayMillis;
    private Supplier<Random> randomSupplier = Random::new;

    RandomBackoff build() {
        requireNonNull(randomSupplier);
        return new RandomBackoff(minDelayMillis, maxDelayMillis, randomSupplier);
    }

    public RandomBackoffBuilder minDelayMillis(long minDelayMillis) {
        this.minDelayMillis = minDelayMillis;
        return this;
    }

    public RandomBackoffBuilder maxDelayMillis(long maxDelayMillis) {
        this.maxDelayMillis = maxDelayMillis;
        return this;
    }

    public RandomBackoffBuilder randomSupplier(Supplier<Random> randomSupplier) {
        this.randomSupplier = randomSupplier;
        return this;
    }
}
