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
    private static final int MAX_VALUE = 5;
    private PartialHealthCheckStrategy maxRatioStrategy;
    private PartialHealthCheckStrategy maxValueStrategy;
    private List<Endpoint> candidatesForMaxRatio;
    private List<Endpoint> candidatesForMaxValue;

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
        maxRatioStrategy = new PartialHealthCheckStrategyBuilder().maxRatio(MAX_RATIO).build();
        maxValueStrategy = new PartialHealthCheckStrategyBuilder().maxValue(MAX_VALUE).build();

        candidatesForMaxRatio = createCandidates(10);
        candidatesForMaxValue = createCandidates(6);

        maxRatioStrategy.updateCandidates(candidatesForMaxRatio);
        maxValueStrategy.updateCandidates(candidatesForMaxValue);
    }

    @Test
    void getCandidatesWhenBeforeFirstUpdateCandidates() {
        maxRatioStrategy = new PartialHealthCheckStrategyBuilder().maxRatio(MAX_RATIO)
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
        final Endpoint unhealthyEndpoint = maxValueStrategy.getCandidates().get(0);

        final boolean actRes = maxValueStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY);
        assertThat(actRes).isTrue();

        final List<Endpoint> actCandidates = maxValueStrategy.getCandidates();
        assertThat(actCandidates).hasSize(5);
        assertThat(actCandidates.contains(unhealthyEndpoint)).isFalse();
        actCandidates.forEach(
                actCandidate -> assertThat(candidatesForMaxValue.contains(actCandidate)).isTrue());
    }

    @Test
    void updateHealthWhenEndpointIsUnhealthyButDoesNotHaveEnoughCandidatesOnMaxRatioMode() {
        final List<Endpoint> endpoints = createCandidates(5);

        maxRatioStrategy = new PartialHealthCheckStrategyBuilder().maxRatio(1).build();
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
        for (Endpoint unhealthyEndpoint : maxValueStrategy.getCandidates()) {
            final boolean actRes = maxValueStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY);
            assertThat(actRes).isTrue();

            final List<Endpoint> actCandidates = maxValueStrategy.getCandidates();
            assertThat(actCandidates).hasSize(5);
            actCandidates.forEach(
                    actCandidate -> assertThat(candidatesForMaxValue.contains(actCandidate)).isTrue());
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
        List<Endpoint> actCandidates = maxValueStrategy.getCandidates();
        final Endpoint candidates = actCandidates.get(0);

        assertThat(actCandidates).hasSize(5);

        boolean actUpdateRes = maxValueStrategy.updateHealth(candidates, UNHEALTHY);
        actCandidates = maxValueStrategy.getCandidates();

        assertThat(actUpdateRes).isTrue();
        assertThat(actCandidates).hasSize(5);
        assertThat(actCandidates.contains(candidates)).isFalse();

        actUpdateRes = maxValueStrategy.updateHealth(candidates, HEALTHY);
        actCandidates = maxValueStrategy.getCandidates();

        assertThat(actUpdateRes).isFalse();
        assertThat(actCandidates).hasSize(5);
        assertThat(actCandidates.contains(candidates)).isFalse();

        actUpdateRes = maxValueStrategy.updateHealth(actCandidates.get(0), UNHEALTHY);
        actCandidates = maxValueStrategy.getCandidates();

        assertThat(actUpdateRes).isTrue();
        assertThat(actCandidates).hasSize(5);
        assertThat(actCandidates.contains(candidates)).isTrue();
    }

    @Test
    void updateHealthByDisappearedCandidate() {
        final Endpoint disappearedCandidate = Endpoint.ofGroup("disappeared");
        final List<Endpoint> candidates = createCandidates(3);

        maxValueStrategy.updateCandidates(candidates);
        assertThat(maxValueStrategy.getCandidates()).hasSize(3);

        boolean actUpdateRes = maxValueStrategy.updateHealth(disappearedCandidate, HEALTHY);
        assertThat(actUpdateRes).isTrue();

        List<Endpoint> actCandidates = maxValueStrategy.getCandidates();
        assertThat(actCandidates).hasSize(3);
        assertThat(actCandidates.contains(disappearedCandidate)).isFalse();

        actUpdateRes = maxValueStrategy.updateHealth(disappearedCandidate, UNHEALTHY);
        assertThat(actUpdateRes).isTrue();

        actCandidates = maxValueStrategy.getCandidates();
        assertThat(actCandidates).hasSize(3);
        assertThat(actCandidates.contains(disappearedCandidate)).isFalse();
    }

    @Test
    void updateCandidates() {
        List<Endpoint> newCandidates = createCandidates(5);
        maxValueStrategy.updateCandidates(newCandidates);

        List<Endpoint> actCandidates = maxValueStrategy.getCandidates();
        assertCandidates(actCandidates, newCandidates);

        newCandidates = candidatesForMaxValue.subList(0, 3);
        maxValueStrategy.updateCandidates(newCandidates);
        actCandidates = maxValueStrategy.getCandidates();

        assertCandidates(actCandidates, newCandidates);

        newCandidates.add(Endpoint.ofGroup("new1"));
        newCandidates.add(Endpoint.ofGroup("new2"));
        maxValueStrategy.updateCandidates(newCandidates);
        actCandidates = maxValueStrategy.getCandidates();

        assertCandidates(actCandidates, newCandidates);
    }
}