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

package com.linecorp.armeria.server.healthcheck;

import static java.util.Objects.requireNonNull;

/**
 * A listener interface for receiving {@link HealthCheckService} update events.
 */
@FunctionalInterface
public interface HealthCheckUpdateListener {

    /**
     * Invoked when the healthiness is updated.
     */
    void healthUpdated(boolean isHealthy) throws Exception;

    /**
     * Invoked when the health status is updated. Override this method for more fine-grained health status
     * updates.
     */
    default void healthStatusUpdated(HealthStatus healthStatus) throws Exception {
        requireNonNull(healthStatus, "healthStatus");
        healthUpdated(healthStatus.isHealthy());
    }
}
