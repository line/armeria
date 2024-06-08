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

/**
 * The health status of a component.
 */
public enum HealthStatus {
    HEALTHY(2, true),
    DEGRADED(1, true),
    UNHEALTHY(0, false);

    private final int priority;
    private final boolean isHealthy;

    HealthStatus(int priority, boolean isHealthy) {
        this.priority = priority;
        this.isHealthy = isHealthy;
    }

    /**
     * Returns the priority of this {@link HealthStatus}.
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Returns whether this {@link HealthStatus} is considered healthy.
     */
    public boolean isHealthy() {
        return isHealthy;
    }
}
