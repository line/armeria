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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightedRoundRobinStrategy.EndpointsAndWeights;
import com.linecorp.armeria.common.CommonPools;

/**
 * A weighted round-robin strategy that ramps up the weight gradually from zero to its normal weight value.
 */
public final class GradualStartEndpointSelectionStrategy implements EndpointSelectionStrategy {
    private static final int DEFAULT_NUMBER_OF_STEPS = 20;

    /**
     * Creates a {@link GradualStartEndpointSelectionStrategyBuilder}.
     */
    public static GradualStartEndpointSelectionStrategyBuilder builder() {
        return new GradualStartEndpointSelectionStrategyBuilder();
    }

    /**
     * Creates a {@link GradualStartEndpointSelectionStrategy}.
     */
    public static EndpointSelectionStrategy of(Duration slowStartInterval) {
        return builder().weightTransition(EndpointWeightTransition.linear())
                        .executorService(CommonPools.workerGroup())
                        .slowStartInterval(slowStartInterval)
                        .numberOfSteps(DEFAULT_NUMBER_OF_STEPS)
                        .build();
    }

    private final EndpointWeightTransition endpointWeightTransition;
    private final ScheduledExecutorService executorService;
    private final Duration slowStartInterval;
    private final int numberOfSteps;

    /**
     * Creates a {@link GradualStartEndpointSelectionStrategy}.
     */
    GradualStartEndpointSelectionStrategy(EndpointWeightTransition endpointWeightTransition,
                                          ScheduledExecutorService executorService,
                                          Duration slowStartInterval,
                                          int numberOfSteps) {
        this.endpointWeightTransition = requireNonNull(endpointWeightTransition, "endpointWeightTransition");
        this.executorService = requireNonNull(executorService, "executorService");
        this.slowStartInterval = requireNonNull(slowStartInterval, "slowStartInterval");
        checkArgument(numberOfSteps > 0, "numberOfSteps(%s) > 0");
        this.numberOfSteps = numberOfSteps;
    }

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new SlowStartAwareWeightedRoundRobinSelector(endpointGroup, endpointWeightTransition);
    }

    /**
     * An {@link EndpointSelector} that is similar to {@link WeightedRoundRobinStrategy} but it
     * increases each {@link Endpoint} weight gradually.
     */
    private final class SlowStartAwareWeightedRoundRobinSelector implements EndpointSelector {
        private final EndpointGroup endpointGroup;
        private final AtomicInteger sequence = new AtomicInteger();
        private final EndpointWeightTransition endpointWeightTransition;
        private final Map<Endpoint, EndpointAndStep> currentWeights = new ConcurrentHashMap<>();

        private volatile EndpointsAndWeights endpointsAndWeights;
        private volatile ScheduledFuture<?> updateEndpointWeightFuture;

        SlowStartAwareWeightedRoundRobinSelector(EndpointGroup endpointGroup,
                                                 EndpointWeightTransition endpointWeightTransition) {
            this.endpointGroup = endpointGroup;
            this.endpointWeightTransition = endpointWeightTransition;
            final List<Endpoint> endpoints = endpointGroup.endpoints();
            endpointsAndWeights = new EndpointsAndWeights(endpoints);
            endpointGroup.addListener(this::scheduleUpdateRequestWeight);
        }

        @Override
        public EndpointGroup group() {
            return endpointGroup;
        }

        @Override
        public EndpointSelectionStrategy strategy() {
            return GradualStartEndpointSelectionStrategy.this;
        }

        @Override
        public Endpoint select(ClientRequestContext ctx) {
            final int currentSequence = sequence.getAndIncrement();
            return endpointsAndWeights.selectEndpoint(currentSequence);
        }

        private void scheduleUpdateRequestWeight(List<Endpoint> newEndpoints) {
            if (updateEndpointWeightFuture != null && !updateEndpointWeightFuture.cancel(false)) {
                updateEndpointWeightFuture = null;
            }
            newEndpoints.forEach(e -> currentWeights.put(e, new EndpointAndStep(e)));
            updateEndpointWeightFuture =
                    executorService.scheduleAtFixedRate(() -> updateEndpointWeight(newEndpoints),
                                                        0,
                                                        slowStartInterval.toMillis(),
                                                        TimeUnit.MILLISECONDS);
        }

        private void updateEndpointWeight(List<Endpoint> endpoints) {
            final AtomicBoolean updated = new AtomicBoolean();
            final List<Endpoint> weightUpdated = endpoints.stream()
                                                          .map(e -> updateWeight(e, updated))
                                                          .collect(toImmutableList());
            if (updated.get()) {
                endpointsAndWeights = new EndpointsAndWeights(weightUpdated);
            } else {
                updateEndpointWeightFuture.cancel(true);
            }
        }

        private Endpoint updateWeight(Endpoint e, AtomicBoolean updated) {
            final EndpointAndStep endpointAndStep = currentWeights.get(e);
            if (endpointAndStep == null) {
                return e;
            }
            final int definedWeight = e.weight();
            final int newWeight = endpointWeightTransition.compute(e, endpointAndStep.stepUp(), numberOfSteps);
            if (newWeight >= definedWeight) {
                currentWeights.remove(e);
                return e;
            }
            updated.set(true);
            return e.withWeight(Math.max(newWeight, 0));
        }
    }

    private static final class EndpointAndStep {
        private final Endpoint endpoint;
        private int step;

        private EndpointAndStep(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        Endpoint endpoint() {
            return endpoint;
        }

        int stepUp() {
            return ++step;
        }
    }

}
