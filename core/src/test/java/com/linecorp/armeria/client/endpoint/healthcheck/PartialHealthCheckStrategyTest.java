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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;

public class PartialHealthCheckStrategyTest {
    private static final double HEALTHY = 1;
    private static final double UNHEALTHY = 0;

    private static final double MAX_RATIO = 0.9;
    private static final int MAX_COUNT = 5;
    private PartialHealthCheckStrategy maxRatioStrategy;
    private PartialHealthCheckStrategy maxCountStrategy;
    private List<Endpoint> candidatesForMaxRatio;
    private List<Endpoint> candidatesForMaxCount;

    private static List<Endpoint> createCandidates(int size) {
        final Random random = new Random();

        return IntStream.range(0, size)
                        .mapToObj(i -> Endpoint.of("dummy" + random.nextInt()))
                        .collect(Collectors.toList());
    }

    private static void assertCandidates(List<Endpoint> act, List<Endpoint> exp) {
        assertThat(act).hasSize(exp.size());

        for (Endpoint expCandidate : exp) {
            assertThat(act.contains(expCandidate)).isTrue();
        }
    }

    @BeforeEach
    void beforeEach() {
        maxRatioStrategy = new PartialHealthCheckStrategyBuilder().maxEndpointRatio(MAX_RATIO).build();
        maxCountStrategy = new PartialHealthCheckStrategyBuilder().maxEndpointCount(MAX_COUNT).build();

        candidatesForMaxRatio = createCandidates(10);
        candidatesForMaxCount = createCandidates(6);

        maxRatioStrategy.updateCandidates(candidatesForMaxRatio);
        maxCountStrategy.updateCandidates(candidatesForMaxCount);
    }

    @Test
    void getCandidatesWhenBeforeFirstUpdateCandidates() {
        maxRatioStrategy = new PartialHealthCheckStrategyBuilder().maxEndpointRatio(MAX_RATIO)
                                                                  .build();

        assertThat(maxRatioStrategy.getCandidates()).isEmpty();
    }

    @Test
    void getCandidatesWhenUpdateCandidatesByEmpty() {
        maxRatioStrategy.updateCandidates(new ArrayList<>());
        assertThat(maxRatioStrategy.getCandidates()).isEmpty();
    }

    @Test
    void getCandidates() {
        maxRatioStrategy.updateCandidates(candidatesForMaxRatio);

        final List<Endpoint> actCandidates = maxRatioStrategy.getCandidates();
        assertThat(actCandidates).hasSize(9);

        actCandidates.forEach(
                actCandidate -> assertThat(candidatesForMaxRatio.contains(actCandidate)).isTrue());
    }

    @Test
    void updateHealthWhenEndpointIsHealthy() {
        final Endpoint endpoint = candidatesForMaxRatio.get(1);

        final boolean actRes = maxRatioStrategy.updateHealth(endpoint, HEALTHY);

        assertThat(actRes).isFalse();
    }

    @Test
    void updateHealthWhenEndpointIsUnhealthyOnMaxRatioMode() {
        final Endpoint unhealthyEndpoint = maxRatioStrategy.getCandidates().get(0);

        final boolean actRes = maxRatioStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY);
        assertThat(actRes).isTrue();

        final List<Endpoint> actCandidates = maxRatioStrategy.getCandidates();
        assertThat(actCandidates).hasSize(9);
        assertThat(actCandidates.contains(unhealthyEndpoint)).isFalse();
        actCandidates.forEach(
                actCandidate -> assertThat(candidatesForMaxRatio.contains(actCandidate)).isTrue());
    }

    @Test
    void updateHealthWhenEndpointIsUnhealthyOnMaxValueMode() {
        final Endpoint unhealthyEndpoint = maxCountStrategy.getCandidates().get(0);

        final boolean actRes = maxCountStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY);
        assertThat(actRes).isTrue();

        final List<Endpoint> actCandidates = maxCountStrategy.getCandidates();
        assertThat(actCandidates).hasSize(5);
        assertThat(actCandidates.contains(unhealthyEndpoint)).isFalse();
        actCandidates.forEach(
                actCandidate -> assertThat(candidatesForMaxCount.contains(actCandidate)).isTrue());
    }

    @Test
    void updateHealthWhenEndpointIsUnhealthyButDoesNotHaveEnoughCandidatesOnMaxRatioMode() {
        final List<Endpoint> endpoints = createCandidates(5);

        maxRatioStrategy = new PartialHealthCheckStrategyBuilder().maxEndpointRatio(1).build();
        maxRatioStrategy.updateCandidates(endpoints);

        for (Endpoint unhealthyEndpoint : maxRatioStrategy.getCandidates()) {
            final boolean actRes = maxRatioStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY);
            assertThat(actRes).isTrue();

            final List<Endpoint> actCandidates = maxRatioStrategy.getCandidates();
            assertThat(actCandidates).hasSize(5);
            actCandidates.forEach(
                    actCandidate -> assertThat(endpoints.contains(actCandidate)).isTrue());
        }
    }

    @Test
    void updateHealthWhenEndpointIsUnhealthyButDoesNotHaveEnoughCandidatesOnMaxValueMode() {
        for (Endpoint unhealthyEndpoint : maxCountStrategy.getCandidates()) {
            final boolean actRes = maxCountStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY);
            assertThat(actRes).isTrue();

            final List<Endpoint> actCandidates = maxCountStrategy.getCandidates();
            assertThat(actCandidates).hasSize(5);
            actCandidates.forEach(
                    actCandidate -> assertThat(candidatesForMaxCount.contains(actCandidate)).isTrue());
        }
    }

    @Test
    void updateHealthWhenMaxRatioMode() {
        List<Endpoint> actCandidates = maxRatioStrategy.getCandidates();
        final Endpoint candidates = actCandidates.get(0);

        assertThat(actCandidates).hasSize(9);

        boolean actUpdateRes = maxRatioStrategy.updateHealth(candidates, UNHEALTHY);
        actCandidates = maxRatioStrategy.getCandidates();

        assertThat(actUpdateRes).isTrue();
        assertThat(actCandidates).hasSize(9);
        assertThat(actCandidates.contains(candidates)).isFalse();

        actUpdateRes = maxRatioStrategy.updateHealth(candidates, HEALTHY);
        actCandidates = maxRatioStrategy.getCandidates();

        assertThat(actUpdateRes).isFalse();
        assertThat(actCandidates).hasSize(9);
        assertThat(actCandidates.contains(candidates)).isFalse();

        actUpdateRes = maxRatioStrategy.updateHealth(actCandidates.get(0), UNHEALTHY);
        actCandidates = maxRatioStrategy.getCandidates();

        assertThat(actUpdateRes).isTrue();
        assertThat(actCandidates).hasSize(9);
        assertThat(actCandidates.contains(candidates)).isTrue();
    }

    @Test
    void updateHealthWhenMaxValueMode() {
        List<Endpoint> actCandidates = maxCountStrategy.getCandidates();
        final Endpoint candidates = actCandidates.get(0);

        assertThat(actCandidates).hasSize(5);

        boolean actUpdateRes = maxCountStrategy.updateHealth(candidates, UNHEALTHY);
        actCandidates = maxCountStrategy.getCandidates();

        assertThat(actUpdateRes).isTrue();
        assertThat(actCandidates).hasSize(5);
        assertThat(actCandidates.contains(candidates)).isFalse();

        actUpdateRes = maxCountStrategy.updateHealth(candidates, HEALTHY);
        actCandidates = maxCountStrategy.getCandidates();

        assertThat(actUpdateRes).isFalse();
        assertThat(actCandidates).hasSize(5);
        assertThat(actCandidates.contains(candidates)).isFalse();

        actUpdateRes = maxCountStrategy.updateHealth(actCandidates.get(0), UNHEALTHY);
        actCandidates = maxCountStrategy.getCandidates();

        assertThat(actUpdateRes).isTrue();
        assertThat(actCandidates).hasSize(5);
        assertThat(actCandidates.contains(candidates)).isTrue();
    }

    @Test
    void updateHealthByDisappearedCandidate() {
        final Endpoint disappearedCandidate = Endpoint.ofGroup("disappeared");
        final List<Endpoint> candidates = createCandidates(3);

        maxCountStrategy.updateCandidates(candidates);
        assertThat(maxCountStrategy.getCandidates()).hasSize(3);

        boolean actUpdateRes = maxCountStrategy.updateHealth(disappearedCandidate, HEALTHY);
        assertThat(actUpdateRes).isTrue();

        List<Endpoint> actCandidates = maxCountStrategy.getCandidates();
        assertThat(actCandidates).hasSize(3);
        assertThat(actCandidates.contains(disappearedCandidate)).isFalse();

        actUpdateRes = maxCountStrategy.updateHealth(disappearedCandidate, UNHEALTHY);
        assertThat(actUpdateRes).isTrue();

        actCandidates = maxCountStrategy.getCandidates();
        assertThat(actCandidates).hasSize(3);
        assertThat(actCandidates.contains(disappearedCandidate)).isFalse();
    }

    @Test
    void updateCandidates() {
        List<Endpoint> newCandidates = createCandidates(5);
        maxCountStrategy.updateCandidates(newCandidates);

        List<Endpoint> actCandidates = maxCountStrategy.getCandidates();
        assertCandidates(actCandidates, newCandidates);

        newCandidates = candidatesForMaxCount.subList(0, 3);
        maxCountStrategy.updateCandidates(newCandidates);
        actCandidates = maxCountStrategy.getCandidates();

        assertCandidates(actCandidates, newCandidates);

        newCandidates.add(Endpoint.ofGroup("new1"));
        newCandidates.add(Endpoint.ofGroup("new2"));
        maxCountStrategy.updateCandidates(newCandidates);
        actCandidates = maxCountStrategy.getCandidates();

        assertCandidates(actCandidates, newCandidates);
    }
}
