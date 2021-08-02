/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common;

/**
 * Event used to initiate graceful connection shutdown.
 */
public final class InitiateConnectionShutdown {
    private static final InitiateConnectionShutdown DEFAULT = new InitiateConnectionShutdown();
    private static final InitiateConnectionShutdown NO_DRAIN = new InitiateConnectionShutdown(0);

    /**
     * Returns {@link InitiateConnectionShutdown} event that uses the currently configured drain duration.
     */
    public static InitiateConnectionShutdown of() {
        return DEFAULT;
    }

    /**
     * Returns {@link InitiateConnectionShutdown} event with custom drain duration in microseconds.
     * Negative values are a valid input - negative duration may be passed as a result of the time arithmetics,
     * in that case drain duration will be set to 0.
     */
    public static InitiateConnectionShutdown of(long drainDurationMicros) {
        // Clamp drain duration to 0. Negative values are used internally to fallback to the currently
        // configured drain duration.
        if (drainDurationMicros <= 0) {
            return NO_DRAIN;
        }
        return new InitiateConnectionShutdown(drainDurationMicros);
    }

    private final long drainDurationMicros;

    private InitiateConnectionShutdown() {
        // Negative value means that drain duration wasn't provided by the caller.
        // Falls back to the currently configured drain duration.
        drainDurationMicros = -1;
    }

    private InitiateConnectionShutdown(long drainDurationMicros) {
        // Negative values are reserved for the default constructor.
        assert drainDurationMicros >= 0;
        this.drainDurationMicros = drainDurationMicros;
    }

    public long drainDurationMicros() {
        return drainDurationMicros;
    }

    public boolean hasCustomDrainDuration() {
        return drainDurationMicros >= 0;
    }
}
