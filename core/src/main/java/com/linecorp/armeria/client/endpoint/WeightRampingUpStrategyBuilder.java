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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.loadbalancer.AbstractRampingUpLoadBalancerBuilder;
import com.linecorp.armeria.common.loadbalancer.WeightTransition;
import com.linecorp.armeria.common.util.Ticker;

import io.netty.util.concurrent.EventExecutor;

/**
 * Builds a weight ramping up {@link EndpointSelectionStrategy} which ramps the weight of newly added
 * {@link Endpoint}s. The {@link Endpoint} is selected using weighted random distribution.
 */
@UnstableApi
public final class WeightRampingUpStrategyBuilder
        extends AbstractRampingUpLoadBalancerBuilder<Endpoint, WeightRampingUpStrategyBuilder> {

    WeightRampingUpStrategyBuilder() {}

    /**
     * Sets the {@link EndpointWeightTransition} which will be used to compute the weight at each step while
     * ramping up. {@link EndpointWeightTransition#linear()} is used by default.
     *
     * @deprecated Use {@link #weightTransition(WeightTransition)} instead.
     */
    @Deprecated
    public WeightRampingUpStrategyBuilder transition(EndpointWeightTransition transition) {
        requireNonNull(transition, "transition");
        return weightTransition((endpoint, weight, currentStep, totalSteps) -> {
            return transition.compute(endpoint, currentStep, totalSteps);
        });
    }

    /**
     * Returns a newly-created weight ramping up {@link EndpointSelectionStrategy} which ramps the weight of
     * newly added {@link Endpoint}s. The {@link Endpoint} is selected using weighted random distribution.
     */
    public EndpointSelectionStrategy build() {
        validate();
        final Supplier<EventExecutor> executorSupplier;
        final EventExecutor executor = executor();
        if (executor != null) {
            executorSupplier = () -> executor;
        } else {
            executorSupplier = () -> CommonPools.workerGroup().next();
        }

        return new WeightRampingUpStrategy(weightTransition(), executorSupplier, rampingUpIntervalMillis(),
                                           totalSteps(), rampingUpTaskWindowMillis(), timestampFunction(),
                                           ticker());
    }

    // Keep these methods for backward compatibility.

    @Override
    public WeightRampingUpStrategyBuilder executor(EventExecutor executor) {
        return super.executor(executor);
    }

    @Override
    public WeightRampingUpStrategyBuilder rampingUpInterval(Duration rampingUpInterval) {
        return super.rampingUpInterval(rampingUpInterval);
    }

    @Override
    public WeightRampingUpStrategyBuilder rampingUpIntervalMillis(long rampingUpIntervalMillis) {
        return super.rampingUpIntervalMillis(rampingUpIntervalMillis);
    }

    @Override
    public WeightRampingUpStrategyBuilder totalSteps(int totalSteps) {
        return super.totalSteps(totalSteps);
    }

    @Override
    public WeightRampingUpStrategyBuilder rampingUpTaskWindow(Duration rampingUpTaskWindow) {
        return super.rampingUpTaskWindow(rampingUpTaskWindow);
    }

    @Override
    public WeightRampingUpStrategyBuilder rampingUpTaskWindowMillis(long rampingUpTaskWindowMillis) {
        return super.rampingUpTaskWindowMillis(rampingUpTaskWindowMillis);
    }

    @Override
    public WeightRampingUpStrategyBuilder timestampFunction(
            Function<? super Endpoint, @Nullable Long> timestampFunction) {
        return super.timestampFunction(timestampFunction);
    }

    @Override
    public WeightRampingUpStrategyBuilder ticker(Ticker ticker) {
        return super.ticker(ticker);
    }
}
