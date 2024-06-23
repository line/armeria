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
package com.linecorp.armeria.common.loadbalancer;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Computes the weight of the given candidate using the given {@code currentStep}
 * and {@code totalSteps}.
 */
@UnstableApi
@FunctionalInterface
public interface WeightTransition<T> {

    /**
     * Returns the {@link WeightTransition} which returns the gradually increased weight as the current
     * step increases.
     */
    static <T> WeightTransition<T> linear() {
        return (candidate, weight, currentStep, totalSteps) ->
                // currentStep is never greater than totalSteps so we can cast long to int.
                Ints.saturatedCast((long) weight * currentStep / totalSteps);
    }

    /**
     * Returns an {@link WeightTransition} which returns a non-linearly increasing weight
     * based on an aggression factor. Higher aggression factors will assign higher weights for lower steps.
     * You may also specify a {@code minWeightPercent} to specify a lower bound for the computed weights.
     * Refer to the following
     * <a href="https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/load_balancing/slow_start">link</a>
     * for more information.
     */
    static <T> WeightTransition<T> aggression(double aggression, double minWeightPercent) {
        checkArgument(aggression > 0,
                      "aggression: %s (expected: > 0.0)", aggression);
        checkArgument(minWeightPercent >= 0 && minWeightPercent <= 1.0,
                      "minWeightPercent: %s (expected: >= 0.0, <= 1.0)", minWeightPercent);
        final int aggressionPercentage = Ints.saturatedCast(Math.round(aggression * 100));
        final double invertedAggression = 100.0 / aggressionPercentage;
        return (candidate, weight, currentStep, totalSteps) -> {
            final int minWeight = Ints.saturatedCast(Math.round(weight * minWeightPercent));
            final int computedWeight;
            if (aggressionPercentage == 100) {
                computedWeight = linear().compute(candidate, weight, currentStep, totalSteps);
            } else {
                computedWeight = (int) (weight * Math.pow(1.0 * currentStep / totalSteps, invertedAggression));
            }
            return Math.max(computedWeight, minWeight);
        };
    }

    /**
     * Returns the computed weight of the given candidate using the given {@code currentStep} and
     * {@code totalSteps}.
     */
    int compute(T candidate, int weight, int currentStep, int totalSteps);
}
