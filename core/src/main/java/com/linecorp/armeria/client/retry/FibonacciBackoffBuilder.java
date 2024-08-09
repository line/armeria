package com.linecorp.armeria.client.retry;

public class FibonacciBackoffBuilder {
    private long initialDelayMillis;
    private long maxDelayMillis;

    FibonacciBackoff build() {
        return new FibonacciBackoff(initialDelayMillis, maxDelayMillis);
    }

    public FibonacciBackoffBuilder initialDelayMillis(long initialDelayMillis) {
        this.initialDelayMillis = initialDelayMillis;
        return this;
    }

    public FibonacciBackoffBuilder maxDelayMillis(long maxDelayMillis) {
        this.maxDelayMillis = maxDelayMillis;
        return this;
    }
}