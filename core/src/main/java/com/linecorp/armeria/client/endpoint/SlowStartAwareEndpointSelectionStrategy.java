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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.WeightedRoundRobinStrategy.EndpointsAndWeights;

import io.netty.util.concurrent.ScheduledFuture;

/**
 * A weighted round-robin strategy that lamp up the weight gradually from zero to its normal weight value.
 */
public final class SlowStartAwareEndpointSelectionStrategy implements EndpointSelectionStrategy {
    private final EndpointWeighter endpointWeighter;
    private final ClientFactory clientFactory;
    private final Duration slowStartInterval;
    private final int numberOfSteps;

    /**
     * Creates a {@link SlowStartAwareEndpointSelectionStrategy}.
     */
    public SlowStartAwareEndpointSelectionStrategy(EndpointWeighter endpointWeighter,
                                                   ClientFactory clientFactory,
                                                   Duration slowStartInterval,
                                                   int numberOfSteps) {
        this.endpointWeighter = endpointWeighter;
        this.clientFactory = clientFactory;
        this.slowStartInterval = requireNonNull(slowStartInterval, "slowStartInterval");
        checkArgument(numberOfSteps > 0, "numberOfSteps(%s) > 0");
        this.numberOfSteps = numberOfSteps;
    }

    @Override
    public SlowStartAwareWeightedRoundRobinSelector newSelector(EndpointGroup endpointGroup) {
        return new SlowStartAwareWeightedRoundRobinSelector(endpointGroup, endpointWeighter);
    }

    /**
     * An {@link EndpointSelector} that is similar to {@link WeightedRoundRobinStrategy} but it
     * increases each {@link Endpoint} weight gradually.
     */
    private final class SlowStartAwareWeightedRoundRobinSelector implements EndpointSelector {
        private final EndpointGroup endpointGroup;
        private final AtomicInteger sequence = new AtomicInteger();
        private volatile EndpointsAndWeights endpointsAndWeights;
        private final EndpointWeighter endpointWeighter;

        private volatile ScheduledFuture<?> updateEndpointWeightFuture = null;
        private final Map<Endpoint, EndpointAndStep> currentWeights = new ConcurrentHashMap<>();

        SlowStartAwareWeightedRoundRobinSelector(EndpointGroup endpointGroup,
                                                 EndpointWeighter endpointWeighter) {
            this.endpointGroup = endpointGroup;
            this.endpointWeighter = endpointWeighter;
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
                    clientFactory.eventLoopGroup()
                                 .scheduleAtFixedRate(() -> updateEndpointWeight(newEndpoints),
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
            int newWeight = endpointWeighter.compute(e, endpointAndStep.stepUp(), numberOfSteps);
            if (originalWeight <= newWeight) {
                currentWeights.remove(e);
                return e;
            }
            return e.withWeight(newWeight);
        }
    }

    private static class EndpointAndStep {
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

    /**
     * Controls an {@link Endpoint} weight to ramp up a request load to the {@link Endpoint}.
     */
    @FunctionalInterface
    public interface EndpointWeighter {
        EndpointWeighter DEFAULT = (e, step, maxStep) -> (int) (e.weight() * (1.0 * step / maxStep * 100));

        /**
         * Computes an {@link Endpoint} weight based on original {@link Endpoint} weight, current/max steps.
         */
        int compute(Endpoint e, int step, int maxStep);
    }
}
