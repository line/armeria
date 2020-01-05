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
package com.linecorp.armeria.client.endpoint.healthcheck;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.jctools.maps.NonBlockingHashSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.Endpoint;

/**
 * A strategy to check part of candidates by health.
 */
final class PartialHealthCheckStrategy implements HealthCheckStrategy {

    /**
     * Creates a new builder.
     */
    public static PartialHealthCheckStrategyBuilder builder() {
        return new PartialHealthCheckStrategyBuilder();
    }

    private final Set<Endpoint> selectedEndpoints;
    private final Set<Endpoint> unhealthyEndpoints;
    private final EndpointLimitingFunction max;
    private Set<Endpoint> candidates;

    /**
     * Creates a new instance.
     */
    PartialHealthCheckStrategy(EndpointLimitingFunction max) {
        this.max = requireNonNull(max, "max");
        selectedEndpoints = new HashSet<>();
        unhealthyEndpoints = new NonBlockingHashSet<>();
        candidates = ImmutableSet.of();
    }

    @Override
    public void updateCandidates(List<Endpoint> candidates) {
        requireNonNull(candidates, "candidates");

        synchronized (selectedEndpoints) {
            this.candidates = ImmutableSet.copyOf(candidates);
            final Set<Endpoint> removedEndpoints = Sets.difference(selectedEndpoints, this.candidates);
            removeAndSelectNewEndpoints(removedEndpoints);
        }
    }

    @Override
    public List<Endpoint> getCandidates() {
        synchronized (selectedEndpoints) {
            return ImmutableList.copyOf(selectedEndpoints);
        }
    }

    @Override
    public boolean updateHealth(Endpoint endpoint, double health) {
        final double unhealthyScore = 0;

        requireNonNull(endpoint, "endpoint");

        if (!candidates.contains(endpoint)) {
            unhealthyEndpoints.remove(endpoint);
            return true;
        }

        if (health > unhealthyScore) {
            unhealthyEndpoints.remove(endpoint);
            return false;
        }

        unhealthyEndpoints.add(endpoint);
        synchronized (selectedEndpoints) {
            return removeAndSelectNewEndpoints(ImmutableSet.of(endpoint));
        }
    }

    /**
     * This method must be called with synchronized selectedEndpoints.
     */
    private boolean removeAndSelectNewEndpoints(Set<Endpoint> removedEndpoints) {
        final Set<Endpoint> oldSelectedEndpoints = ImmutableSet.copyOf(selectedEndpoints);
        final int targetSelectedEndpointsSize = max.calculate(candidates.size());

        selectedEndpoints.removeAll(removedEndpoints);

        int availableEndpointsCount = calculateAvailableEndpointsCount(targetSelectedEndpointsSize);
        if (availableEndpointsCount <= 0) {
            return true;
        }

        final int newSelectedEndpointsCount = addRandomlySelectedEndpoints(selectedEndpoints, candidates,
                                                                           availableEndpointsCount,
                                                                           selectedEndpoints,
                                                                           unhealthyEndpoints);

        availableEndpointsCount -= newSelectedEndpointsCount;
        if (availableEndpointsCount <= 0) {
            return true;
        }

        addRandomlySelectedEndpoints(selectedEndpoints, unhealthyEndpoints, availableEndpointsCount,
                                     selectedEndpoints);

        return !oldSelectedEndpoints.equals(selectedEndpoints);
    }

    @SafeVarargs
    private static int addRandomlySelectedEndpoints(Set<Endpoint> selectedEndpoints,
                                                    Set<Endpoint> candidates, int count,
                                                    Set<Endpoint>... exclusions) {
        final List<Endpoint> availableCandidates = new ArrayList<>(candidates.size());
        loop:
        for (Endpoint candidate : candidates) {
            for (Set<Endpoint> exclusion : exclusions) {
                if (exclusion.contains(candidate)) {
                    continue loop;
                }
            }

            availableCandidates.add(candidate);
        }

        int newSelectedEndpointsCount = 0;
        final Random random = ThreadLocalRandom.current();
        for (int i = 0; i < count && !availableCandidates.isEmpty(); i++) {
            newSelectedEndpointsCount++;
            selectedEndpoints.add(availableCandidates.remove(random.nextInt(availableCandidates.size())));
        }

        return newSelectedEndpointsCount;
    }

    private int calculateAvailableEndpointsCount(int targetSelectedEndpointsSize) {
        return Math.min(candidates.size() - selectedEndpoints.size(),
                        targetSelectedEndpointsSize - selectedEndpoints.size());
    }

    static final class EndpointLimitingFunction {

        private final int count;
        private final double ratio;
        private final boolean ratioMode;

        private EndpointLimitingFunction(int count, double ratio, boolean ratioMode) {
            this.count = count;
            this.ratio = ratio;
            this.ratioMode = ratioMode;
        }

        static EndpointLimitingFunction ofCount(int count) {
            checkArgument(count > 0, "count: %s (expected: 0 < count <= MAX_INT)", count);
            return new EndpointLimitingFunction(count, 0, false);
        }

        static EndpointLimitingFunction ofRatio(double ratio) {
            checkArgument(0 < ratio && ratio <= 1, "ratio: %s (expected: 0 < ratio <= 1)",
                          ratio);

            return new EndpointLimitingFunction(0, ratio, true);
        }

        int calculate(int numCandidates) {
            if (ratioMode) {
                return Math.max(1, (int) (numCandidates * ratio));
            } else {
                return count;
            }
        }
    }
}
