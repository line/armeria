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
 * under the Licenses
 */

package com.linecorp.armeria.server.healthcheck;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The result of health check with interval for next check.
 */
@UnstableApi
public final class HealthCheckStatus {
    private final boolean isHealthy;
    private final long ttlMillis;

    /**
     * Create the result of the health check.
     *
     * @param isHealthy health check result
     * @param ttlMillis interval for scheduling the next check
     */
    public HealthCheckStatus(boolean isHealthy, long ttlMillis) {
        this.isHealthy = isHealthy;
        this.ttlMillis = ttlMillis;
    }

    /**
     * Return the result of health check.
     */
    public boolean isHealthy() {
        return isHealthy;
    }

    /**
     * Return the interval for scheduling the next check.
     */
    public long ttlMillis() {
        return ttlMillis;
    }

    @Override
    public String toString() {
        return "HealthCheckStatus{" +
               "isHealthy=" + isHealthy +
               ", ttlMillis=" + ttlMillis +
               '}';
    }
}
