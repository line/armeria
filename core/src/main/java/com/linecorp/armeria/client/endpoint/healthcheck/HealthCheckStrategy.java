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

@FunctionalInterface
interface HealthCheckStrategy {

    /**
     * Returns a strategy to check all candidates.
     */
    static HealthCheckStrategy all() {
        return candidates -> candidates;
    }

    /**
     * Sets the maximum endpoint count of target selected candidates.
     * The maximum endpoint count must greater than 0.
     * You can use only one of the maximum endpoint count or maximum endpoint ratio.
     */
    static HealthCheckStrategy ofCount(int maxEndpointCount) {
        return PartialHealthCheckStrategy.builder()
                                         .maxEndpointCount(maxEndpointCount)
                                         .build();
    }

    /**
     * Sets the maximum endpoint ratio of target selected candidates.
     * The maximum endpoint ratio must greater than 0 and less or equal to 1.
     * You can use only one of the maximum endpoint count or maximum endpoint ratio.
     */
    static HealthCheckStrategy ofRatio(double maxEndpointRatio) {
        return PartialHealthCheckStrategy.builder()
                                         .maxEndpointRatio(maxEndpointRatio)
                                         .build();
    }

    /**
     * Returns {@link Endpoint}s selected by this health check strategy.
     */
    List<Endpoint> select(List<Endpoint> candidates);
}

