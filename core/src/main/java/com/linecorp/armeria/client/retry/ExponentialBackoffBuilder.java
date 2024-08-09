package com.linecorp.armeria.client.retry;

public class ExponentialBackoffBuilder {
    private long initialDelayMillis;
    private long maxDelayMillis;
    private double multiplier;

    ExponentialBackoff build() {
        return new ExponentialBackoff(initialDelayMillis, maxDelayMillis, multiplier);
    }

    public ExponentialBackoffBuilder initialDelayMillis(long initialDelayMillis) {
        this.initialDelayMillis = initialDelayMillis;
        return this;
    }

    public ExponentialBackoffBuilder maxDelayMillis(long maxDelayMillis) {
        this.maxDelayMillis = maxDelayMillis;
        return this;
    }

    public ExponentialBackoffBuilder multiplier(double multiplier) {
        this.multiplier = multiplier;
        return this;
    }
}
