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

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.loadbalancer.WeightTransition;

/**
 * Computes the weight of the given {@link Endpoint} using the given {@code currentStep}
 * and {@code totalSteps}.
 *
 * @deprecated Use {@link WeightTransition} instead.
 */
@Deprecated
@FunctionalInterface
public interface EndpointWeightTransition {

    /**
     * Returns the {@link EndpointWeightTransition} which returns the gradually increased weight as the current
     * step increases.
     *
     * @deprecated Use {@link WeightTransition#linear()} instead.
     */
    @Deprecated
    static EndpointWeightTransition linear() {
        return (endpoint, currentStep, totalSteps) -> {
            return WeightTransition.linear().compute(endpoint, endpoint.weight(), currentStep, totalSteps);
        };
    }

    /**
     * Returns an {@link EndpointWeightTransition} which returns a non-linearly increasing weight
     * based on an aggression factor. Higher aggression factors will assign higher weights for lower steps.
     * You may also specify a {@code minWeightPercent} to specify a lower bound for the computed weights.
     * Refer to the following
     * <a href="https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/load_balancing/slow_start">link</a>
     * for more information.
     *
     * @deprecated Use {@link WeightTransition#aggression(double, double)} instead.
     */
    @Deprecated
    static EndpointWeightTransition aggression(double aggression, double minWeightPercent) {
        checkArgument(aggression > 0,
                      "aggression: %s (expected: > 0.0)", aggression);
        checkArgument(minWeightPercent >= 0 && minWeightPercent <= 1.0,
                      "minWeightPercent: %s (expected: >= 0.0, <= 1.0)", minWeightPercent);
        return (endpoint, currentStep, totalSteps) -> {
            return WeightTransition.aggression(aggression, minWeightPercent)
                                   .compute(endpoint, endpoint.weight(), currentStep, totalSteps);
        };
    }

    /**
     * Returns the computed weight of the given {@link Endpoint} using the given {@code currentStep} and
     * {@code totalSteps}.
     */
    int compute(Endpoint endpoint, int currentStep, int totalSteps);
}
