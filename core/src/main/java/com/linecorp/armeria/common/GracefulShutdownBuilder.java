/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Builds a {@link GracefulShutdown}.
 */
@UnstableApi
public final class GracefulShutdownBuilder {

    // Defaults to no graceful shutdown.
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD = Duration.ZERO;
    private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ZERO;

    static final GracefulShutdown DISABLED = GracefulShutdown.builder().build();

    private Duration quietPeriod = DEFAULT_GRACEFUL_SHUTDOWN_QUIET_PERIOD;
    private Duration timeout = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT;

    GracefulShutdownBuilder() {}

    private static Duration validateNonNegative(Duration duration, String fieldName) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + ": " + duration + " (expected: >= 0)");
        }
        return duration;
    }

    /**
     * Sets the quiet period to wait for active requests to go end before shutting down.
     * {@link Duration#ZERO} means the client or server (depending on the place of usage) will stop right away
     * without waiting.
     *
     * <p>The default is {@link Duration#ZERO}.
     */
    public GracefulShutdownBuilder quietPeriod(Duration quietPeriod) {
        requireNonNull(quietPeriod, "quietPeriod");
        this.quietPeriod = validateNonNegative(quietPeriod, "quietPeriod");
        return this;
    }

    /**
     * Sets the quiet period millis to wait for active requests to go end before shutting down.
     * 0 means the client or server (depending on the place of usage) will stop right away without waiting.
     *
     * <p>The default is 0.
     */
    public GracefulShutdownBuilder quietPeriodMillis(long quietPeriodMillis) {
        return quietPeriod(Duration.ofMillis(quietPeriodMillis));
    }

    /**
     * Sets the amount of time to wait before shutting down the client or server (depending on the place of
     * usage) regardless of active requests. This should be set to a time greater than {@code quietPeriod} to
     * ensure the client/server shuts down even if there is a stuck request.
     *
     * <p>The default is {@link Duration#ZERO}.
     */
    public GracefulShutdownBuilder timeout(Duration timeout) {
        requireNonNull(timeout, "timeout");
        this.timeout = validateNonNegative(timeout, "timeout");
        return this;
    }

    /**
     * Sets the amount of time to wait before shutting down the client or server (depending on the place of
     * usage) regardless of active requests. This should be set to a time greater than {@code quietPeriod} to
     * ensure the client/server shuts down even if there is a stuck request.
     *
     * <p>The default is {@link Duration#ZERO}.
     */
    public GracefulShutdownBuilder timeoutMillis(long timeoutMillis) {
        return timeout(Duration.ofMillis(timeoutMillis));
    }

    private static void validateGreaterThanOrEqual(Duration larger, String largerFieldName,
                                           Duration smaller, String smallerFieldName) {
        if (larger.compareTo(smaller) < 0) {
            throw new IllegalArgumentException(largerFieldName + " must be greater than or equal to " +
                                               smallerFieldName);
        }
    }

    /**
     * Builds a new {@link GracefulShutdown} with the configured parameters.
     */
    public GracefulShutdown build() {
        validateGreaterThanOrEqual(timeout, "timeout", quietPeriod, "quietPeriod");
        return new DefaultGracefulShutdown(quietPeriod, timeout);
    }
}
