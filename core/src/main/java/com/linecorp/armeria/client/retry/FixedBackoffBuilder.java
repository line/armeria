package com.linecorp.armeria.client.retry;

public class FixedBackoffBuilder {
    private long delayMillis;

    FixedBackoff build() {
        return new FixedBackoff(delayMillis);
    }

    public FixedBackoffBuilder delayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
        return this;
    }
}