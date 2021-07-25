package com.linecorp.armeria.internal.common;

import java.time.Duration;

public final class InitiateConnectionShutdown {
    private final Duration gracePeriod;

    public InitiateConnectionShutdown() {
        this(Duration.ZERO);
    }

    public InitiateConnectionShutdown(Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    public Duration gracePeriod() { return gracePeriod; }
}
