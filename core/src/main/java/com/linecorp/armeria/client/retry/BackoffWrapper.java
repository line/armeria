package com.linecorp.armeria.client.retry;

class BackoffWrapper implements Backoff {
    private final Backoff delegate;

    protected BackoffWrapper(Backoff delegate) {
        this.delegate = delegate;
    }

    @Override
    public long nextIntervalMillis(int numAttemptsSoFar) {
        return delegate.nextIntervalMillis(numAttemptsSoFar);
    }

    protected Backoff delegate() {
        return delegate;
    }
}
