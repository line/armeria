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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Supplier;

import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

/**
 * Builds a weight ramping up {@link EndpointSelectionStrategy} which ramps the weight of newly added
 * {@link Endpoint}s. The {@link Endpoint} is selected using weighted random distribution.
 */
@UnstableApi
public final class WeightRampingUpStrategyBuilder {

    static final long DEFAULT_RAMPING_UP_INTERVAL_MILLIS = 2000;
    static final int DEFAULT_TOTAL_STEPS = 10;
    static final int DEFAULT_RAMPING_UP_TASK_WINDOW_MILLIS = 500;
    static final EndpointWeightTransition DEFAULT_LINEAR_TRANSITION =
            (endpoint, currentStep, totalSteps) ->
                    // currentStep is never greater than totalSteps so we can cast long to int.
                    Ints.saturatedCast((long) endpoint.weight() * currentStep / totalSteps);
    static final EndpointWeightTransition defaultTransition = EndpointWeightTransition.linear();

    private EndpointWeightTransition transition = defaultTransition;

    @Nullable
    private EventExecutor executor;

    private long rampingUpIntervalMillis = DEFAULT_RAMPING_UP_INTERVAL_MILLIS;
    private int totalSteps = DEFAULT_TOTAL_STEPS;
    private long rampingUpTaskWindowMillis = DEFAULT_RAMPING_UP_TASK_WINDOW_MILLIS;

    /**
     * Sets the {@link EndpointWeightTransition} which will be used to compute the weight at each step while
     * ramping up. {@link EndpointWeightTransition#linear()} is used by default.
     */
    public WeightRampingUpStrategyBuilder transition(EndpointWeightTransition transition) {
        this.transition = requireNonNull(transition, "transition");
        return this;
    }

    /**
     * Sets the {@link EventExecutor} to use to execute tasks for computing new weights. An {@link EventLoop}
     * from {@link CommonPools#workerGroup()} is used by default.
     */
    public WeightRampingUpStrategyBuilder executor(EventExecutor executor) {
        this.executor = requireNonNull(executor, "executor");
        return this;
    }

    /**
     * Sets the interval between weight updates during ramp up.
     * {@value DEFAULT_RAMPING_UP_INTERVAL_MILLIS} millis is used by default.
     */
    public WeightRampingUpStrategyBuilder rampingUpInterval(Duration rampingUpInterval) {
        requireNonNull(rampingUpInterval, "rampingUpInterval");
        return rampingUpIntervalMillis(rampingUpInterval.toMillis());
    }

    /**
     * Sets the interval between weight updates during ramp up.
     * {@value DEFAULT_RAMPING_UP_INTERVAL_MILLIS} millis is used by default.
     */
    public WeightRampingUpStrategyBuilder rampingUpIntervalMillis(long rampingUpIntervalMillis) {
        checkArgument(rampingUpIntervalMillis > 0,
                      "rampingUpIntervalMillis: %s (expected: > 0)", rampingUpIntervalMillis);
        this.rampingUpIntervalMillis = rampingUpIntervalMillis;
        return this;
    }

    /**
     * Sets the total number of steps to compute weights for a given {@link Endpoint} while ramping up.
     * {@value DEFAULT_TOTAL_STEPS} is used by default.
     */
    public WeightRampingUpStrategyBuilder totalSteps(int totalSteps) {
        checkArgument(totalSteps > 0, "totalSteps: %s (expected: > 0)", totalSteps);
        this.totalSteps = totalSteps;
        return this;
    }

    /**
     * Sets the window for combining weight update tasks.
     * If more than one {@link Endpoint} are added within the {@code rampingUpTaskWindow}, the weights of
     * them are ramped up together. If there's already a scheduled job and new {@link Endpoint}s are added
     * within the {@code rampingUpTaskWindow}, they are also ramped up together.
     * This is an example of how it works when {@code rampingUpTaskWindow} is 500 milliseconds and
     * {@code rampingUpIntervalMillis} is 2000 milliseconds:
     * <pre>{@code
     * ----------------------------------------------------------------------------------------------------
     *     A         B                             C                                       D
     *     t0        t1                            t2                                      t3         t4
     * ----------------------------------------------------------------------------------------------------
     *     0ms       t0 + 200ms                    t0 + 1000ms                          t0 + 1800ms  t0 + 2000ms
     * }</pre>
     * A and B are ramped up right away when they are added and they are ramped up together at t4.
     * C is ramped up alone every 2000 milliseconds. D is ramped up together with A and B at t4.
     */
    public WeightRampingUpStrategyBuilder rampingUpTaskWindow(Duration rampingUpTaskWindow) {
        requireNonNull(rampingUpTaskWindow, "rampingUpTaskWindow");
        return rampingUpTaskWindowMillis(rampingUpTaskWindow.toMillis());
    }

    /**
     * Sets the window for combining weight update tasks.
     * If more than one {@link Endpoint} are added within the {@code rampingUpTaskWindowMillis},
     * the weights of them are ramped up together. If there's already a scheduled job and
     * new {@link Endpoint}s are added within the {@code rampingUpTaskWindow}, they are also ramped up together.
     * This is an example of how it works when {@code rampingUpTaskWindowMillis} is 500 milliseconds and
     * {@code rampingUpIntervalMillis} is 2000 milliseconds:
     * <pre>{@code
     * ----------------------------------------------------------------------------------------------------
     *     A         B                             C                                       D
     *     t0        t1                            t2                                      t3         t4
     * ----------------------------------------------------------------------------------------------------
     *     0ms       t0 + 200ms                    t0 + 1000ms                          t0 + 1800ms  t0 + 2000ms
     * }</pre>
     * A and B are ramped up right away when they are added and they are ramped up together at t4.
     * C is ramped up alone every 2000 milliseconds. D is ramped up together with A and B at t4.
     */
    public WeightRampingUpStrategyBuilder rampingUpTaskWindowMillis(long rampingUpTaskWindowMillis) {
        checkArgument(rampingUpTaskWindowMillis >= 0,
                      "rampingUpTaskWindowMillis: %s (expected >= 0)", rampingUpTaskWindowMillis);
        this.rampingUpTaskWindowMillis = rampingUpTaskWindowMillis;
        return this;
    }

    /**
     * Returns a newly-created weight ramping up {@link EndpointSelectionStrategy} which ramps the weight of
     * newly added {@link Endpoint}s. The {@link Endpoint} is selected using weighted random distribution.
     */
    public EndpointSelectionStrategy build() {
        checkState(rampingUpIntervalMillis > rampingUpTaskWindowMillis,
                   "rampingUpIntervalMillis: %s, rampingUpTaskWindowMillis: %s " +
                   "(expected: rampingUpIntervalMillis > rampingUpTaskWindowMillis)",
                   rampingUpIntervalMillis, rampingUpTaskWindowMillis);
        final Supplier<EventExecutor> executorSupplier;
        if (executor != null) {
            executorSupplier = () -> executor;
        } else {
            executorSupplier = () -> CommonPools.workerGroup().next();
        }

        return new WeightRampingUpStrategy(transition, executorSupplier, rampingUpIntervalMillis,
                                           totalSteps, rampingUpTaskWindowMillis);
    }
}
