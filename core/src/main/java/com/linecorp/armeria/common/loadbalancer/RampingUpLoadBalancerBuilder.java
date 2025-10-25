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

import java.util.List;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.concurrent.EventExecutor;

/**
 * A builder for creating a {@link RampingUpLoadBalancer}.
 */
@UnstableApi
public final class RampingUpLoadBalancerBuilder<T>
        extends AbstractRampingUpLoadBalancerBuilder<T, RampingUpLoadBalancerBuilder<T>> {

    private final List<T> candidates;
    @Nullable
    private final ToIntFunction<T> weightFunction;

    RampingUpLoadBalancerBuilder(Iterable<T> candidates, @Nullable ToIntFunction<T> weightFunction) {
        this.candidates = ImmutableList.copyOf(candidates);
        this.weightFunction = weightFunction;
    }

    /**
     * Returns a newly-created weight ramping up {@link LoadBalancer} which ramps the weight of
     * newly added candidates. The candidate is selected using weighted random distribution.
     */
    public UpdatableLoadBalancer<T> build() {
        validate();

        EventExecutor executor = executor();
        if (executor == null) {
            executor = CommonPools.workerGroup().next();
        }

        return new RampingUpLoadBalancer<>(candidates, weightFunction, rampingUpIntervalMillis(), totalSteps(),
                                           rampingUpTaskWindowMillis(), weightTransition(), timestampFunction(),
                                           ticker(),
                                           executor);
    }
}
