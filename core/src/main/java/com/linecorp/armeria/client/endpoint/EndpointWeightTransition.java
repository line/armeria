/*
 * Copyright 2020 LINE Corporation
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

import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.Endpoint;

/**
 * Computes the weight of the given {@link Endpoint} using the given {@code currentStep}
 * and {@code numberSteps}.
 */
@FunctionalInterface
public interface EndpointWeightTransition {

    /**
     * Returns the {@link EndpointWeightTransition} which returns the gradually increased weight as the current
     * step increases.
     */
    static EndpointWeightTransition linear() {
        return (endpoint, currentStep, numberSteps) ->
                // currentStep is never greater than numberSteps so we can cast long to int.
                Ints.saturatedCast((long) endpoint.weight() * currentStep / numberSteps);
    }

    /**
     * Returns the computed weight of the given {@link Endpoint} using the given {@code currentStep} and
     * {@code numberSteps}.
     */
    int compute(Endpoint endpoint, int currentStep, int numberSteps);
}
