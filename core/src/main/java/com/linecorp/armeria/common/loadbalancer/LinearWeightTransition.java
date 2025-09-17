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

import com.google.common.primitives.Ints;

final class LinearWeightTransition<T> implements WeightTransition<T> {

    static final LinearWeightTransition<?> INSTANCE = new LinearWeightTransition<>();

    @Override
    public int compute(T candidate, int weight, int currentStep, int totalSteps) {
        // currentStep is never greater than totalSteps so we can cast long to int.
        final int currentWeight =
                Ints.saturatedCast((long) weight * currentStep / totalSteps);
        if (weight > 0 && currentWeight == 0) {
            // If the original weight is not 0,
            // we should return 1 to make sure the endpoint is selected.
            return 1;
        }
        return currentWeight;
    }

    @Override
    public String toString() {
        return "WeightTransition.linear()";
    }
}
