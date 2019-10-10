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

import com.linecorp.armeria.client.Endpoint;

public class AllHealthCheckStrategy implements HealthCheckStrategy {

    private List<Endpoint> candidates;

    public AllHealthCheckStrategy() {
        candidates = NOT_CHANGE;
    }

    @Override
    public void updateCandidate(List<Endpoint> candidates) {
        this.candidates = requireNonNull(candidates, "candidates");
    }

    @Override
    public List<Endpoint> getCandidates() {
        return candidates;
    }
}
