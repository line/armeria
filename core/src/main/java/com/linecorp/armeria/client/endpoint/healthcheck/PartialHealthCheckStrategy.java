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

import com.linecorp.armeria.client.Endpoint;

import com.google.common.collect.ImmutableSet;

/**
 * A strategy to check part of candidates by health.
 */
public class PartialHealthCheckStrategy implements HealthCheckStrategy {

    private final Set<Endpoint> selectedCandidates;
    private final Set<Endpoint> unhealthyCandidates;
    private final TargetCount max;
    private Set<Endpoint> candidates;

    /**
     * Creates a new instance.
     */
    PartialHealthCheckStrategy(TargetCount max) {
        this.max = requireNonNull(max, "max");
        selectedCandidates = new NonBlockingHashSet<>();
        unhealthyCandidates = new NonBlockingHashSet<>();
        candidates = ImmutableSet.copyOf(new ArrayList<>());
    }

    private static Set<Endpoint> selectRandomly(Set<Endpoint> availableCandidates, int cnt) {
        final List<Endpoint> endpoints = new ArrayList<>(availableCandidates);

        final Set<Endpoint> selectedCandidates = new HashSet<>();
        final Random random = ThreadLocalRandom.current();

        for (int i = 0; i < cnt && !endpoints.isEmpty(); i++) {
            selectedCandidates.add(endpoints.remove(random.nextInt(endpoints.size())));
        }

        return selectedCandidates;
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

            updateSelectedCandidates(removedCandidates);
        }
    }

    @Override
    public List<Endpoint> getCandidates() {
        synchronized (selectedCandidates) {
            return new ArrayList<>(selectedCandidates);
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
            updateSelectedCandidates(ImmutableSet.of(endpoint));
            return true;
        }
    }

    /*
    This method must be called with synchronized selectedCandidates
     */
    private void updateSelectedCandidates(final Set<Endpoint> removedEndpoints) {
        final int targetSelectedCandidatesSize = max.calculate(candidates.size());

        removedEndpoints.forEach(selectedCandidates::remove);

        int availableCandidateCount = calculateAvailableCandidateCount(targetSelectedCandidatesSize);
        if (availableCandidateCount <= 0) {
            return;
        }

        final Set<Endpoint> availableCandidates = new HashSet<>(candidates);
        availableCandidates.removeAll(selectedCandidates);
        availableCandidates.removeAll(unhealthyCandidates);

        final Set<Endpoint> newSelectedCandidates = selectRandomly(availableCandidates,
                                                                   availableCandidateCount);
        selectedCandidates.addAll(newSelectedCandidates);

        availableCandidateCount -= newSelectedCandidates.size();
        if (availableCandidateCount <= 0) {
            return;
        }

        final Set<Endpoint> availableUnhealthyCandidates = new HashSet<>(unhealthyCandidates);
        availableUnhealthyCandidates.removeAll(selectedCandidates);

        selectedCandidates.addAll(selectRandomly(availableUnhealthyCandidates, availableCandidateCount));
    }

    private int calculateAvailableCandidateCount(int targetSelectedCandidatesSize) {
        return Math.min(candidates.size() - selectedCandidates.size(),
                        targetSelectedCandidatesSize - selectedCandidates.size());
    }

    static final class TargetCount {

        private final int value;
        private final double ratio;
        private final boolean ratioMode;
        private TargetCount(int value, double ratio, boolean ratioMode) {
            this.value = value;
            this.ratio = ratio;
            this.ratioMode = ratioMode;
        }

        static TargetCount ofValue(int value) {
            checkArgument(value > 0, "value: %s (expected: 1 - MAX_INT)", value);
            return new TargetCount(value, 0, false);
        }

        static TargetCount ofRatio(double ratio) {
            checkArgument(0 < ratio && ratio <= 1, "ratio: %s (expected: 0.x - 1)",
                          ratio);

            return new TargetCount(0, ratio, true);
        }

        int calculate(int candidates) {
            if (ratioMode) {
                return Math.max(1, (int) (candidates * ratio));
            } else {
                return value;
            }
        }
    }
}
