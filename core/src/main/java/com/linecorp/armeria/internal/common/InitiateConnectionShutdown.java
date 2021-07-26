package com.linecorp.armeria.internal.common;

import java.time.Duration;

import javax.annotation.Nullable;

public final class InitiateConnectionShutdown {
    @Nullable
    private final Duration gracePeriod;

    public InitiateConnectionShutdown(@Nullable Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    @Nullable
    public Duration gracePeriod() { return gracePeriod; }
}
