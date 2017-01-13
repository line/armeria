package com.linecorp.armeria.client.backoff;

final class AttemptLimitingBackoff implements Backoff {
    private final Backoff backoff;
    private final int maxRetries;

    AttemptLimitingBackoff(Backoff backoff, int maxRetries) {
        this.backoff = backoff;
        this.maxRetries = maxRetries;
    }

    @Override
    public long nextIntervalMillis() {
        return backoff.nextIntervalMillis();
    }

    @Override
    public int maxRetries() {
        return maxRetries;
    }
}
