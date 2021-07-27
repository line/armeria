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

public final class InitiateConnectionShutdown {
    private final long drainDurationMicros;

    public InitiateConnectionShutdown() {
        // Negative value means that drain duration wasn't provided by the caller.
        // Fallback to the currently configured drain duration.
        drainDurationMicros = -1;
    }

    public InitiateConnectionShutdown(long drainDurationMicros) {
        // Clamp drain duration to 0, negative values are reserved for fallback to the default.
        // Users can still pass results of the duration arithmetics directly, if duration is negative
        // it just means graceful drain should be skipped.
        this.drainDurationMicros = Math.max(drainDurationMicros, 0);
    }

    public long drainDurationMicros() {
        return drainDurationMicros;
    }
}
