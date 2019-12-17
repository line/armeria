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

    private final Set<Endpoint> selectedCandidates;
    private final Set<Endpoint> unhealthyCandidates;
    private final TargetCount max;
    private Set<Endpoint> candidates;

    /**
     * Creates a new instance.
     */
    PartialHealthCheckStrategy(TargetCount max) {
        this.max = requireNonNull(max, "max");
        selectedCandidates = new HashSet<>();
        unhealthyCandidates = new NonBlockingHashSet<>();
        candidates = ImmutableSet.of();
    }

    @Override
    public void updateCandidates(List<Endpoint> candidates) {
        requireNonNull(candidates, "candidates");

        synchronized (selectedCandidates) {
            this.candidates = ImmutableSet.copyOf(candidates);

            final Set<Endpoint> removedCandidates = new HashSet<>();
            for (Endpoint selectedCandidate : selectedCandidates) {
                if (this.candidates.contains(selectedCandidate)) {
                    continue;
                }

                removedCandidates.add(selectedCandidate);
            }

            removeAndSelectNewCandidates(removedCandidates);
        }
    }

    @Override
    public List<Endpoint> getCandidates() {
        synchronized (selectedCandidates) {
            return ImmutableList.copyOf(selectedCandidates);
        }
    }

    @Override
    public boolean updateHealth(Endpoint endpoint, double health) {
        final double unhealthyScore = 0;

        requireNonNull(endpoint, "endpoint");

        if (!candidates.contains(endpoint)) {
            unhealthyCandidates.remove(endpoint);
            return true;
        }

        if (health > unhealthyScore) {
            unhealthyCandidates.remove(endpoint);
            return false;
        }

        unhealthyCandidates.add(endpoint);
        synchronized (selectedCandidates) {
            return removeAndSelectNewCandidates(ImmutableSet.of(endpoint));
        }
    }

    /**
     This method must be called with synchronized selectedCandidates.
     */
    private boolean removeAndSelectNewCandidates(final Set<Endpoint> removedEndpoints) {
        final Set<Endpoint> oldSelectedCandidates = ImmutableSet.copyOf(selectedCandidates);
        final int targetSelectedCandidatesSize = max.calculate(candidates.size());

        selectedCandidates.removeAll(removedEndpoints);

        int availableCandidateCount = calculateAvailableCandidateCount(targetSelectedCandidatesSize);
        if (availableCandidateCount <= 0) {
            return true;
        }

        final int newSelectedCandidatesCount = addRandomlySelectedCandidates(selectedCandidates, candidates,
                                                                             availableCandidateCount,
                                                                             selectedCandidates,
                                                                             unhealthyCandidates);

        availableCandidateCount -= newSelectedCandidatesCount;
        if (availableCandidateCount <= 0) {
            return true;
        }

        addRandomlySelectedCandidates(selectedCandidates, unhealthyCandidates, availableCandidateCount,
                                      selectedCandidates);

        return !oldSelectedCandidates.equals(selectedCandidates);
    }

    @SafeVarargs
    private static int addRandomlySelectedCandidates(Set<Endpoint> selectedCandidates,
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

        int newSelectedCandidateCount = 0;
        final Random random = ThreadLocalRandom.current();
        for (int i = 0; i < count && !availableCandidates.isEmpty(); i++) {
            newSelectedCandidateCount++;
            selectedCandidates.add(availableCandidates.remove(random.nextInt(availableCandidates.size())));
        }

        return newSelectedCandidateCount;
    }

    private int calculateAvailableCandidateCount(int targetSelectedCandidatesSize) {
        return Math.min(candidates.size() - selectedCandidates.size(),
                        targetSelectedCandidatesSize - selectedCandidates.size());
    }

    static final class TargetCount {

        private final int count;
        private final double ratio;
        private final boolean ratioMode;

        private TargetCount(int count, double ratio, boolean ratioMode) {
            this.count = count;
            this.ratio = ratio;
            this.ratioMode = ratioMode;
        }

        static TargetCount ofCount(int count) {
            checkArgument(count > 0, "count: %s (expected: 0 < count <= MAX_INT)", count);
            return new TargetCount(count, 0, false);
        }

        static TargetCount ofRatio(double ratio) {
            checkArgument(0 < ratio && ratio <= 1, "ratio: %s (expected: 0 < ratio <= 1)",
                          ratio);

            return new TargetCount(0, ratio, true);
        }

        int calculate(int candidates) {
            if (ratioMode) {
                return Math.max(1, (int) (candidates * ratio));
            } else {
                return count;
            }
        }
    }
}
