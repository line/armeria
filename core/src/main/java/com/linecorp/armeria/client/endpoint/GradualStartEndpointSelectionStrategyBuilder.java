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
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

/**
 * A builder for creating a new {@link GradualStartEndpointSelectionStrategy} that ramps up the weight
 * gradually.
 */
public final class GradualStartEndpointSelectionStrategyBuilder {
    private EndpointWeightTransition endpointWeightTransition;
    private ScheduledExecutorService executorService;
    private Duration totalSlowStartDuration;
    private Duration slowStartInterval;
    private int numberOfSteps;

    GradualStartEndpointSelectionStrategyBuilder() {
    }

    /**
     * Sets an {@link EndpointWeightTransition}.
     */
    public GradualStartEndpointSelectionStrategyBuilder weightTransition(
            EndpointWeightTransition endpointWeightTransition) {
        this.endpointWeightTransition = requireNonNull(endpointWeightTransition, "endpointWeightTransition");
        return this;
    }

    /**
     * Sets a {@link ScheduledExecutorService} that runs a future that ramps up each endpoint weights.
     */
    public GradualStartEndpointSelectionStrategyBuilder executorService(
            ScheduledExecutorService executorService) {
        this.executorService = requireNonNull(executorService, "executorService");
        return this;
    }

    /**
     * Sets a total duration of slow start.
     */
    public GradualStartEndpointSelectionStrategyBuilder totalSlowStartDuration(
            Duration totalSlowStartDuration) {
        checkState(slowStartInterval == null, "slowStartInterval is already given");
        this.totalSlowStartDuration = requireNonNull(totalSlowStartDuration,
                                                     "totalSlowStartDuration");
        return this;
    }

    /**
     * Sets an intervals during slow start.
     */
    public GradualStartEndpointSelectionStrategyBuilder slowStartInterval(Duration slowStartInterval) {
        checkState(totalSlowStartDuration == null, "totalSlowStartDuration is already given");
        this.slowStartInterval = requireNonNull(slowStartInterval, "slowStartInterval");
        return this;
    }

    /**
     * Sets a number of steps to complete slow start.
     */
    public GradualStartEndpointSelectionStrategyBuilder numberOfSteps(int numberOfSteps) {
        checkArgument(numberOfSteps > 0, "numberOfSteps: %s (expected: > 0)", numberOfSteps);
        this.numberOfSteps = numberOfSteps;
        return this;
    }

    /**
     * Creates a {@link GradualStartEndpointSelectionStrategy}.
     */
    public GradualStartEndpointSelectionStrategy build() {
        final Duration duration;
        if (totalSlowStartDuration != null) {
            duration = Duration.ofMillis(totalSlowStartDuration.toMillis() / numberOfSteps);
        } else {
            duration = slowStartInterval;
        }
        return new GradualStartEndpointSelectionStrategy(
                endpointWeightTransition,
                executorService,
                duration,
                numberOfSteps);
    }
}
