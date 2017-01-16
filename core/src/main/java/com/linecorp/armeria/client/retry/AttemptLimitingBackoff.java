package com.linecorp.armeria.client.retry;

final class AttemptLimitingBackoff extends BackoffWrapper {
    private final int maxAttempts;

    AttemptLimitingBackoff(Backoff backoff, int maxAttempts) {
        super(backoff);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public long nextIntervalMillis(int numAttemptsSoFar) {
        if (numAttemptsSoFar >= maxAttempts) {
            return -1;
        }
        return super.nextIntervalMillis(numAttemptsSoFar);
    }
}
