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

package com.linecorp.armeria.server.healthcheck;

import com.linecorp.armeria.server.Server;

/**
 * The health status of a {@link Server}.
 */
@UnstableApi
public enum HealthStatus {
    HEALTHY(500, true),
    DEGRADED(400, true),
    STOPPING(300, false),
    UNHEALTHY(200, false),
    UNDER_MAINTENANCE(100, false);

    private final int priority;
    private final boolean isAvailable;

    HealthStatus(int priority, boolean isAvailable) {
        this.priority = priority;
        this.isAvailable = isAvailable;
    }

    /**
     * Returns the priority of this {@link HealthStatus}.
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Returns whether this {@link HealthStatus} is considered available.
     */
    public boolean isAvailable() {
        return isAvailable;
    }
}
