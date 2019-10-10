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

import org.jctools.maps.NonBlockingHashSet;

import com.linecorp.armeria.client.Endpoint;

import com.google.common.collect.ImmutableSet;

public class PartialHealthCheckStrategy implements HealthCheckStrategy {

    private final Set<Endpoint> selectedCandidates;

    private final double percentage;
    private final int minimumSelectedCandidates;

    private List<Endpoint> candidates;

    private volatile boolean change;

    public PartialHealthCheckStrategy(double percentage, int minimumSelectedCandidates) {
        checkArgument(percentage > 0 && percentage <= 100, "percentage: %s (expected: 0.x-100)", percentage);
        this.percentage = percentage;
        checkArgument(minimumSelectedCandidates > 0, "minimumSelectedCandidates: %s (expected: 1 - INT_MAX)",
                      minimumSelectedCandidates);
        this.minimumSelectedCandidates = minimumSelectedCandidates;
        selectedCandidates = new NonBlockingHashSet<>();
        candidates = new ArrayList<>();
        change = false;
    }

    @Override
    public void updateCandidate(List<Endpoint> candidates) {
        synchronized (selectedCandidates) {
            this.candidates = requireNonNull(candidates, "candidates");

            final Set<Endpoint> removedCandidates = new HashSet<>();
            for (Endpoint candidate : this.candidates) {
                if (selectedCandidates.contains(candidate)) {
                    continue;
                }

                removedCandidates.add(candidate);
            }

            updateSelectedCandidates(removedCandidates);
        }
    }

    @Override
    public List<Endpoint> getCandidates() {
        if (!change) {
            return NOT_CHANGE;
        }

        synchronized (selectedCandidates) {
            change = false;

            return new ArrayList<>(selectedCandidates);
        }
    }

    @Override
    public void updateHealth(Endpoint endpoint, double health) {
        final double unhealthyScore = 0;

        requireNonNull(endpoint, "endpoint");

        if (health > unhealthyScore) {
            return;
        }

        synchronized (selectedCandidates) {
            updateSelectedCandidates(ImmutableSet.of(endpoint));
        }
    }

    /*
    This method must be called with synchronized selectedCandidates
     */
    private void updateSelectedCandidates(final Set<Endpoint> removedEndpoints) {
        final int maxSelectedCandidates = Math.max(minimumSelectedCandidates,
                                                   (int) (candidates.size() * percentage / 100));

        change = true;
        removedEndpoints.forEach(selectedCandidates::remove);

        final int availableCandidateCount = availableCandidateCount(maxSelectedCandidates);
        if (availableCandidateCount <= 0) {
            return;
        }

        final Set<Endpoint> availableCandidates = new HashSet<>(candidates);
        availableCandidates.removeAll(selectedCandidates);

        selectedCandidates.addAll(selectRandomly(availableCandidates, availableCandidateCount));
    }

    private Set<Endpoint> selectRandomly(Set<Endpoint> availableCandidates, int cnt) {
        final List<Endpoint> endpoints = new ArrayList<>(availableCandidates);

        final Set<Endpoint> selectedCandidates = new HashSet<>();

        final Random random = new Random();

        for (int i = 0; i < cnt || i < availableCandidates.size(); i++) {
            selectedCandidates.add(endpoints.remove(random.nextInt(endpoints.size())));
        }

        return selectedCandidates;
    }

    private int availableCandidateCount(int maxSelectedCandidates) {
        return Math.min(candidates.size() - selectedCandidates.size(),
                        maxSelectedCandidates - selectedCandidates.size());
    }
}
