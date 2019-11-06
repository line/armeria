/*
 * Copyright 2019 LINE Corporation
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
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

/**
 * A builder for creating a new {@link SlowStartAwareEndpointSelectionStrategy} that ramps up the weight
 * gradually.
 */
public final class SlowStartAwareEndpointSelectionStrategyBuilder {
    private EndpointWeightTransition endpointWeightTransition;
    private ScheduledExecutorService executorService;
    private Duration totalSlowStartDuration;
    private Duration slowStartInterval;
    private int numberOfSteps;

    /**
     * Sets an {@link EndpointWeightTransition}.
     */
    public SlowStartAwareEndpointSelectionStrategyBuilder endpointWeighter(
            EndpointWeightTransition endpointWeightTransition) {
        this.endpointWeightTransition = requireNonNull(endpointWeightTransition, "endpointWeightTransition");
        return this;
    }

    /**
     * Sets a {@link ScheduledExecutorService} that runs a future that ramps up each endpoint weights.
     */
    public SlowStartAwareEndpointSelectionStrategyBuilder executorService(
            ScheduledExecutorService executorService) {
        this.executorService = requireNonNull(executorService, "executorService");
        return this;
    }

    /**
     * Sets a total duration of slow start.
     */
    public SlowStartAwareEndpointSelectionStrategyBuilder totalSlowStartDuration(
            Duration totalSlowStartDuration) {
        this.totalSlowStartDuration = requireNonNull(totalSlowStartDuration,
                                                     "totalSlowStartDuration");
        return this;
    }

    /**
     * Sets an intervals during slow start.
     */
    public SlowStartAwareEndpointSelectionStrategyBuilder slowStartInterval(Duration slowStartInterval) {
        if (totalSlowStartDuration != null) {
            throw new IllegalArgumentException("totalSlowStartDuration is already given");
        }
        this.slowStartInterval = requireNonNull(slowStartInterval, "slowStartInterval");
        return this;
    }

    /**
     * Sets a number of steps to complete slow start.
     */
    public SlowStartAwareEndpointSelectionStrategyBuilder numberOfSteps(int numberOfSteps) {
        checkArgument(numberOfSteps > 0, "numberOfSteps: %s (expected: > 0)", numberOfSteps);
        this.numberOfSteps = numberOfSteps;
        return this;
    }

    /**
     * Creates a {@link SlowStartAwareEndpointSelectionStrategy}.
     */
    public SlowStartAwareEndpointSelectionStrategy build() {
        final Duration interval;
        if (totalSlowStartDuration != null) {
            interval = Duration.ofMillis(totalSlowStartDuration.toMillis() / numberOfSteps);
        } else {
            interval = slowStartInterval;
        }
        return new SlowStartAwareEndpointSelectionStrategy(
                endpointWeightTransition,
                executorService,
                interval,
                numberOfSteps);
    }
}
