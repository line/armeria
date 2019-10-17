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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.linecorp.armeria.client.Endpoint;

import com.google.common.collect.ImmutableSet;

/**
 * A strategy to check all of candidates.
 */
public class AllHealthCheckStrategy implements HealthCheckStrategy {

    private Set<Endpoint> candidates;

    public AllHealthCheckStrategy() {
        candidates = new HashSet<>();
    }

    @Override
    public void updateCandidates(List<Endpoint> candidates) {
        requireNonNull(candidates, "candidates");
        this.candidates = ImmutableSet.copyOf(candidates);
    }

    @Override
    public List<Endpoint> getCandidates() {
        return new ArrayList<>(candidates);
    }

    @Override
    public boolean updateHealth(Endpoint endpoint, double health) {
        requireNonNull(endpoint, "endpoint");
        return isDisappeared(endpoint);
    }

    private boolean isDisappeared(Endpoint endpoint) {
        return !candidates.contains(endpoint);
    }
}
