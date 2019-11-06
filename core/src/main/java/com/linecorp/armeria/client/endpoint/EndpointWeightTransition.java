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

package com.linecorp.armeria.client.endpoint;

import com.linecorp.armeria.client.Endpoint;

/**
 * Controls an {@link Endpoint} weight to ramp up a request load to the {@link Endpoint}.
 */
@FunctionalInterface
public interface EndpointWeightTransition {
    static EndpointWeightTransition linear() {
        return (endpoint, currentSteps, totalSteps) ->
                (int) ((double) endpoint.weight() * currentSteps / totalSteps);
    }

    /**
     * Computes an {@link Endpoint} weight based on original {@link Endpoint} weight, current/total steps.
     */
    int compute(Endpoint endpoint, int currentSteps, int totalSteps);
}
