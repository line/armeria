package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Wraps an existing {@link Backoff}.
 */
public class BackoffWrapper implements Backoff {
    private final Backoff delegate;

    protected BackoffWrapper(Backoff delegate) {
        this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public long nextIntervalMillis(int numAttemptsSoFar) {
        return delegate.nextIntervalMillis(numAttemptsSoFar);
    }

    protected Backoff delegate() {
        return delegate;
    }
}
