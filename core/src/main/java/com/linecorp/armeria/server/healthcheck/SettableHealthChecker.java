/*
 * Copyright 2015 LINE Corporation
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
 * A simple {@link HealthChecker} whose state can be set by a caller. This can be used in case server health
 * should have additional conditions besides the state of the {@link Server}. e.g. it should depend on the
 * health of a backend.
 */
public final class SettableHealthChecker implements HealthChecker {

    private volatile boolean isHealthy;

    @Override
    public boolean isHealthy() {
        return isHealthy;
    }

    /**
     * Sets if the {@link Server} is healthy or not.
     */
    public void setHealthy(boolean isHealthy) {
        this.isHealthy = isHealthy;
    }

    @Override
    public String toString() {
        return "SettableHealthChecker: " + (isHealthy ? "healthy" : "not healthy");
    }
}
