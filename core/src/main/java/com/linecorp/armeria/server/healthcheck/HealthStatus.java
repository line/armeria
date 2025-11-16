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

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;

/**
 * The health status of a {@link Server}.
 */
@UnstableApi
public enum HealthStatus {
    /**
     * The {@link Server} is healthy and able to serve requests.
     */
    HEALTHY(500, true),
    /**
     * The {@link Server} is degraded and may not be able to handle requests as much as the server with
     * {@link HealthStatus#HEALTHY} status.
     */
    DEGRADED(400, true),
    /**
     * The {@link Server} is stopping and unable to serve requests. This status is set when
     * {@link ServerListener#serverStopping(Server)} is called by default.
     */
    STOPPING(300, false),
    /**
     * The {@link Server} is unhealthy and unable to serve requests.
     */
    UNHEALTHY(200, false),
    /**
     * The {@link Server} is under maintenance and unable to serve requests.
     */
    UNDER_MAINTENANCE(100, false);

    private final int priority;
    private final boolean isHealthy;

    HealthStatus(int priority, boolean isHealthy) {
        this.priority = priority;
        this.isHealthy = isHealthy;
    }

    /**
     * Returns the priority of this {@link HealthStatus}.
     */
    public int priority() {
        return priority;
    }

    /**
     * Returns whether this {@link HealthStatus} is considered healthy.
     */
    public boolean isHealthy() {
        return isHealthy;
    }
}
