/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.healthcheck;

import java.util.List;

import com.linecorp.armeria.client.Endpoint;

interface HealthCheckStrategy {

    /**
     * Updates the candidates.
     * @param candidates the {@link Endpoint} used to select based on implementation.
     */
    void updateCandidates(List<Endpoint> candidates);

    /**
     * Returns {@link Endpoint}s selected by this health check strategy.
     */
    List<Endpoint> getSelectedEndpoints();

    /**
     * Updates the health of the {@link Endpoint}.
     * @param endpoint the {@link Endpoint} to update health.
     * @param health {@code 0.0} indicates the {@link Endpoint} is not able to handle any requests.
     *               A positive value indicates the {@link Endpoint} is able to handle requests.
     *               A value greater than {@code 1.0} will be set equal to {@code 1.0}.
     * @return the result of candidates updated by update health.
     */
    boolean updateHealth(Endpoint endpoint, double health);
}

