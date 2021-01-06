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
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.CommonPools;

import io.netty.channel.EventLoop;

/**
 * Builds a weight ramping up {@link EndpointSelectionStrategy} which ramps the weight of newly added
 * {@link Endpoint}s. The {@link Endpoint} is selected using weighted random distribution.
 */
public final class WeightRampingUpStrategyBuilder {

    private static final long DEFAULT_RAMPING_UP_INTERVAL_MILLIS = 2000;
    private static final int DEFAULT_NUMBER_OF_STEPS = 10;
    private static final int DEFAULT_RAMPING_UP_ENTRY_WINDOW_MILLIS = 500;

    private EndpointWeightTransition transition = EndpointWeightTransition.linear();

    @Nullable
    private ScheduledExecutorService executor;

    private long rampingUpIntervalMillis = DEFAULT_RAMPING_UP_INTERVAL_MILLIS;
    private int totalSteps = DEFAULT_NUMBER_OF_STEPS;
    private long rampingUpTaskWindowMillis = DEFAULT_RAMPING_UP_ENTRY_WINDOW_MILLIS;

    /**
     * Sets computing the weight of an {@link Endpoint} during the transition with the specified
     * {@link EndpointWeightTransition}. {@link EndpointWeightTransition#linear()} is used by default.
     */
    public WeightRampingUpStrategyBuilder transition(EndpointWeightTransition transition) {
        this.transition = requireNonNull(transition, "transition");
        return this;
    }

    /**
     * Sets the specified {@link ScheduledExecutorService} that will be used to compute the weight of
     * an {@link Endpoint} using {@link EndpointWeightTransition}. An {@link EventLoop} from
     * {@link CommonPools#workerGroup()} is used by default.
     */
    public WeightRampingUpStrategyBuilder executor(ScheduledExecutorService executor) {
        this.executor = requireNonNull(executor, "executor");
        return this;
    }

    /**
     * Sets the specified {@code rampingUpInterval}. The weight of an {@link Endpoint} is ramped up
     * every {@code rampingUpInterval} until the number of ramping reaches to the
     * {@link #totalSteps(int) totalSteps}.
     * {@value DEFAULT_RAMPING_UP_INTERVAL_MILLIS} millis is used by default.
     */
    public WeightRampingUpStrategyBuilder rampingUpInterval(Duration rampingUpInterval) {
        requireNonNull(rampingUpInterval, "rampingUpInterval");
        return rampingUpIntervalMillis(rampingUpInterval.toMillis());
    }

    /**
     * Sets the specified {@code rampingUpIntervalMillis}. The weight of an {@link Endpoint} is ramped up
     * every {@code rampingUpIntervalMillis} until the number of ramping reaches to the
     * {@link #totalSteps(int) totalSteps}.
     * {@value DEFAULT_RAMPING_UP_INTERVAL_MILLIS} millis is used by default.
     */
    public WeightRampingUpStrategyBuilder rampingUpIntervalMillis(long rampingUpIntervalMillis) {
        checkArgument(rampingUpIntervalMillis > 0,
                      "rampingUpIntervalMillis: %s (expected: > 0)", rampingUpIntervalMillis);
        this.rampingUpIntervalMillis = rampingUpIntervalMillis;
        return this;
    }

    /**
     * Sets the specified {@code totalSteps}. The weight of an {@link Endpoint} is ramped up
     * every {@link #rampingUpIntervalMillis(long) rampingUpIntervalMillis}
     * until the number of ramping reaches to the {@code totalSteps}.
     * {@value DEFAULT_NUMBER_OF_STEPS} is used by default.
     */
    public WeightRampingUpStrategyBuilder totalSteps(int totalSteps) {
        checkArgument(totalSteps > 0, "totalSteps: %s (expected: > 0)", totalSteps);
        this.totalSteps = totalSteps;
        return this;
    }

    /**
     * Sets the specified {@code rampingUpTaskWindow} which will be used to combine weight ramping up task.
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
     * Sets the specified {@code rampingUpTaskWindowMillis} which will be used to combine weight ramping up
     * tasks. If more than one {@link Endpoint} are added within the {@code rampingUpTaskWindowMillis},
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
        final ScheduledExecutorService executor;
        if (this.executor != null) {
            executor = this.executor;
        } else {
            executor = CommonPools.workerGroup().next();
        }

        return new WeightRampingUpStrategy(transition, executor, rampingUpIntervalMillis,
                                           totalSteps, rampingUpTaskWindowMillis);
    }
}
