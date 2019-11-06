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
import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightedRoundRobinStrategy.EndpointsAndWeights;

/**
 * A weighted round-robin strategy that ramps up the weight gradually from zero to its normal weight value.
 */
public final class SlowStartAwareEndpointSelectionStrategy implements EndpointSelectionStrategy {

    /**
     * Creates a {@link SlowStartAwareEndpointSelectionStrategyBuilder}.
     */
    public static SlowStartAwareEndpointSelectionStrategyBuilder builder() {
        return new SlowStartAwareEndpointSelectionStrategyBuilder();
    }

    /**
     * Creates a {@link SlowStartAwareEndpointSelectionStrategy}.
     */
    public static EndpointSelectionStrategy of(EndpointWeightTransition endpointWeightTransition,
                                               ScheduledExecutorService executorService,
                                               Duration slowStartInterval,
                                               int numberOfSteps) {
        return builder().endpointWeighter(endpointWeightTransition)
                        .executorService(executorService)
                        .slowStartInterval(slowStartInterval)
                        .numberOfSteps(numberOfSteps)
                        .build();
    }

    private final EndpointWeightTransition endpointWeightTransition;
    private final ScheduledExecutorService executorService;
    private final Duration slowStartInterval;
    private final int numberOfSteps;

    /**
     * Creates a {@link SlowStartAwareEndpointSelectionStrategy}.
     */
    SlowStartAwareEndpointSelectionStrategy(EndpointWeightTransition endpointWeightTransition,
                                            ScheduledExecutorService executorService,
                                            Duration slowStartInterval,
                                            int numberOfSteps) {
        this.endpointWeightTransition = endpointWeightTransition;
        this.executorService = executorService;
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
        private volatile EndpointsAndWeights endpointsAndWeights;
        private final EndpointWeightTransition endpointWeightTransition;

        private volatile ScheduledFuture<?> updateEndpointWeightFuture;
        private final Map<Endpoint, EndpointAndStep> currentWeights = new ConcurrentHashMap<>();

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
            return SlowStartAwareEndpointSelectionStrategy.this;
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
            endpointsAndWeights = new EndpointsAndWeights(
                    endpoints.stream()
                             .map(this::updateWeight)
                             .collect(toImmutableList()));
        }

        private Endpoint updateWeight(Endpoint e) {
            final EndpointAndStep endpointAndStep = currentWeights.get(e);
            if (endpointAndStep == null) {
                return e;
            }
            final int originalWeight = e.weight();
            final int newWeight = endpointWeightTransition.compute(e, endpointAndStep.stepUp(), numberOfSteps);
            if (newWeight >= originalWeight) {
                currentWeights.remove(e);
                return e;
            }
            return e.withWeight(newWeight >= 0 ? newWeight : 0);
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
