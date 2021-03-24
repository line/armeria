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

public final class HealthCheckStatus {
    // The current healthiness
    private boolean isHealthy;
    // When we need to check again
    private long ttlMillis;

    public HealthCheckStatus(boolean isHealthy, long ttlMillis) {
        this.isHealthy = isHealthy;
        this.ttlMillis = ttlMillis;
    }

    public boolean isHealthy() {
        return isHealthy;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }
}
