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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Ticker;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

/**
 * A skeletal builder implementation for building a ramping up {@link LoadBalancer}.
 */
@UnstableApi
public abstract class AbstractRampingUpLoadBalancerBuilder<
        T, SELF extends AbstractRampingUpLoadBalancerBuilder<T, SELF>> {

    private static final long DEFAULT_RAMPING_UP_INTERVAL_MILLIS = 2000;
    private static final int DEFAULT_TOTAL_STEPS = 10;
    private static final int DEFAULT_RAMPING_UP_TASK_WINDOW_MILLIS = 500;
    private static final Function<?, @Nullable Long> DEFAULT_TIMESTAMP_FUNCTION = c -> null;

    private WeightTransition<T> weightTransition = WeightTransition.linear();
    @Nullable
    private EventExecutor executor;
    private long rampingUpIntervalMillis = DEFAULT_RAMPING_UP_INTERVAL_MILLIS;
    private int totalSteps = DEFAULT_TOTAL_STEPS;
    private long rampingUpTaskWindowMillis = DEFAULT_RAMPING_UP_TASK_WINDOW_MILLIS;
    private Ticker ticker = Ticker.systemTicker();
    @SuppressWarnings("unchecked")
    private Function<T, Long> timestampFunction = (Function<T, Long>) DEFAULT_TIMESTAMP_FUNCTION;

    /**
     * Creates a new instance.
     */
    protected AbstractRampingUpLoadBalancerBuilder() {}

    /**
     * Sets the {@link WeightTransition} which will be used to compute the weight at each step while
     * ramping up. {@link WeightTransition#linear()} is used by default.
     */
    public final SELF weightTransition(WeightTransition<T> transition) {
        weightTransition = requireNonNull(transition, "transition");
        return self();
    }

    /**
     * Returns the {@link WeightTransition} which will be used to compute the weight at each step while ramping
     * up.
     */
    protected final WeightTransition<T> weightTransition() {
        return weightTransition;
    }

    /**
     * Sets the {@link EventExecutor} to use to execute tasks for computing new weights. An {@link EventLoop}
     * from {@link CommonPools#workerGroup()} is used by default.
     */
    public SELF executor(EventExecutor executor) {
        this.executor = requireNonNull(executor, "executor");
        return self();
    }

    /**
     * Returns the {@link EventExecutor} to use to execute tasks for computing new weights.
     */
    @Nullable
    protected final EventExecutor executor() {
        return executor;
    }

    /**
     * Sets the interval between weight updates during ramp up.
     * {@value DEFAULT_RAMPING_UP_INTERVAL_MILLIS} millis is used by default.
     */
    public SELF rampingUpInterval(Duration rampingUpInterval) {
        requireNonNull(rampingUpInterval, "rampingUpInterval");
        return rampingUpIntervalMillis(rampingUpInterval.toMillis());
    }

    /**
     * Sets the interval between weight updates during ramp up.
     * {@value DEFAULT_RAMPING_UP_INTERVAL_MILLIS} millis is used by default.
     */
    public SELF rampingUpIntervalMillis(long rampingUpIntervalMillis) {
        checkArgument(rampingUpIntervalMillis > 0,
                      "rampingUpIntervalMillis: %s (expected: > 0)", rampingUpIntervalMillis);
        this.rampingUpIntervalMillis = rampingUpIntervalMillis;
        return self();
    }

    /**
     * Returns the interval between weight updates during ramp up.
     */
    protected final long rampingUpIntervalMillis() {
        return rampingUpIntervalMillis;
    }

    /**
     * Sets the total number of steps to compute weights for a given candidate while ramping up.
     * {@value DEFAULT_TOTAL_STEPS} is used by default.
     */
    public SELF totalSteps(int totalSteps) {
        checkArgument(totalSteps > 0, "totalSteps: %s (expected: > 0)", totalSteps);
        this.totalSteps = totalSteps;
        return self();
    }

    /**
     * Returns the total number of steps to compute weights for a given candidate while ramping up.
     */
    protected final int totalSteps() {
        return totalSteps;
    }

    /**
     * Sets the window for combining weight update tasks.
     * If more than one candidate are added within the {@code rampingUpTaskWindow}, the weights of
     * them are ramped up together. If there's already a scheduled job and new candidates are added
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
    public SELF rampingUpTaskWindow(Duration rampingUpTaskWindow) {
        requireNonNull(rampingUpTaskWindow, "rampingUpTaskWindow");
        return rampingUpTaskWindowMillis(rampingUpTaskWindow.toMillis());
    }

    /**
     * Sets the window for combining weight update tasks.
     * If more than one candidate are added within the {@code rampingUpTaskWindowMillis},
     * the weights of them are ramped up together. If there's already a scheduled job and
     * new candidates are added within the {@code rampingUpTaskWindow}, they are also ramped up together.
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
    public SELF rampingUpTaskWindowMillis(long rampingUpTaskWindowMillis) {
        checkArgument(rampingUpTaskWindowMillis >= 0,
                      "rampingUpTaskWindowMillis: %s (expected >= 0)", rampingUpTaskWindowMillis);
        this.rampingUpTaskWindowMillis = rampingUpTaskWindowMillis;
        return self();
    }

    /**
     * Returns the window for combining weight update tasks.
     */
    protected final long rampingUpTaskWindowMillis() {
        return rampingUpTaskWindowMillis;
    }

    /**
     * Sets the timestamp function to use to get the creation time of the given candidate.
     * The timestamp is used to calculate the ramp up weight of the candidate.
     * If {@code null} is returned or the timestamp function is not set, the timestamp is set to the current
     * time when the candidate is added.
     */
    public SELF timestampFunction(Function<? super T, @Nullable Long> timestampFunction) {
        requireNonNull(timestampFunction, "timestampFunction");
        //noinspection unchecked
        this.timestampFunction = (Function<T, Long>) timestampFunction;
        return self();
    }

    /**
     * Returns the timestamp function to use to get the creation time of the given candidate.
     */
    protected final Function<T, Long> timestampFunction() {
        return timestampFunction;
    }

    /**
     * Sets the {@link Ticker} to use to measure time. {@link Ticker#systemTicker()} is used by default.
     */
    public SELF ticker(Ticker ticker) {
        requireNonNull(ticker, "ticker");
        this.ticker = ticker;
        return self();
    }

    /**
     * Returns the {@link Ticker} to use to measure time.
     */
    protected final Ticker ticker() {
        return ticker;
    }

    private SELF self() {
        @SuppressWarnings("unchecked")
        final SELF self = (SELF) this;
        return self;
    }

    /**
     * Validates the properties of this builder.
     */
    protected final void validate() {
        checkState(rampingUpIntervalMillis > rampingUpTaskWindowMillis,
                   "rampingUpIntervalMillis: %s, rampingUpTaskWindowMillis: %s " +
                   "(expected: rampingUpIntervalMillis > rampingUpTaskWindowMillis)",
                   rampingUpIntervalMillis, rampingUpTaskWindowMillis);
    }
}
