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

import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;

/**
 * A strategy to check all of candidates.
 */
final class AllHealthCheckStrategy implements HealthCheckStrategy {

    private Set<Endpoint> candidates;

    AllHealthCheckStrategy() {
        candidates = ImmutableSet.of();
    }

    @Override
    public void updateCandidates(List<Endpoint> candidates) {
        requireNonNull(candidates, "candidates");
        this.candidates = ImmutableSet.copyOf(candidates);
    }

    @Override
    public List<Endpoint> getCandidates() {
        return ImmutableList.copyOf(candidates);
    }

    @Override
    public boolean updateHealth(Endpoint endpoint, double health) {
        requireNonNull(endpoint, "endpoint");
        return !candidates.contains(endpoint);
    }
}
