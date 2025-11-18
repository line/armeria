/*
 * Copyright 2025 LINE Corporation
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

import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;

final class AggregationWeightTransition<T> implements WeightTransition<T> {

    private final double aggressionPercentage;
    private final double invertedAggression;
    private final double minWeightPercent;

    AggregationWeightTransition(double aggression, double minWeightPercent) {
        aggressionPercentage = Ints.saturatedCast(Math.round(aggression * 100));
        invertedAggression = 100.0 / aggressionPercentage;
        this.minWeightPercent = minWeightPercent;
    }

    @Override
    public int compute(T candidate, int weight, int currentStep, int totalSteps) {
        final int minWeight = Ints.saturatedCast(Math.round(weight * minWeightPercent));
        final int computedWeight;
        if (aggressionPercentage == 100) {
            computedWeight = WeightTransition.<T>linear().compute(candidate, weight, currentStep, totalSteps);
        } else {
            computedWeight = (int) (weight * Math.pow(1.0 * currentStep / totalSteps,
                                                      invertedAggression));
        }
        return Math.max(computedWeight, minWeight);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("aggressionPercentage", aggressionPercentage)
                          .add("invertedAggression", invertedAggression)
                          .add("minWeightPercent", minWeightPercent)
                          .toString();
    }
}
