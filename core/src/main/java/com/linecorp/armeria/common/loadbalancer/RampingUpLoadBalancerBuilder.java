/*
 * Copyright 2024 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.ToIntFunction;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.concurrent.EventExecutor;

/**
 * A builder for creating a {@link RampingUpLoadBalancer}.
 */
@UnstableApi
public final class RampingUpLoadBalancerBuilder<T, C>
        extends AbstractRampingUpLoadBalancerBuilder<T, RampingUpLoadBalancerBuilder<T, C>> {

    private static final ToIntFunction<?> DEFAULT_WEIGHT_FUNCTION = c -> 1000;

    private final List<T> candidates;

    @SuppressWarnings("unchecked")
    private ToIntFunction<T> weightFunction = (ToIntFunction<T>) DEFAULT_WEIGHT_FUNCTION;

    RampingUpLoadBalancerBuilder(Iterable<T> candidates) {
        this.candidates = ImmutableList.copyOf(candidates);
    }

    /**
     * Sets the weight function to use to get the weight of the given candidate.
     * The weight is used to calculate the ramp up weight of the candidate.
     * If not set, the weight is set to 1000.
     */
    public RampingUpLoadBalancerBuilder<T, C> weightFunction(ToIntFunction<T> weightFunction) {
        requireNonNull(weightFunction, "weightFunction");
        this.weightFunction = weightFunction;
        return this;
    }

    /**
     * Returns a newly-created weight ramping up {@link LoadBalancer} which ramps the weight of
     * newly added candidates. The candidate is selected using weighted random distribution.
     */
    public UpdatableLoadBalancer<T, C> build() {
        validate();

        EventExecutor executor = executor();
        if (executor == null) {
            executor = CommonPools.workerGroup().next();
        }

        return new RampingUpLoadBalancer<>(candidates, rampingUpIntervalMillis(), totalSteps(),
                                           rampingUpTaskWindowMillis(), ticker(), weightTransition(),
                                           weightFunction, timestampFunction(), executor);
    }
}
