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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;

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
                        .collect(toImmutableList());
    }

    private static void assertCandidates(List<Endpoint> actualCandidates, List<Endpoint> expectedCandidates) {
        assertThat(actualCandidates).hasSize(expectedCandidates.size());

        for (Endpoint expectedCandidate : expectedCandidates) {
            assertThat(actualCandidates).contains(expectedCandidate);
        }
    }

    private static void assertUniqueCandidates(List<Endpoint> candidates) {
        if (candidates.isEmpty()) {
            return;
        }

        assertThat(candidates).hasSameSizeAs(ImmutableSet.copyOf(candidates));
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
    void getCandidatesAfterSettingEmptyCandidates() {
        maxRatioStrategy.updateCandidates(new ArrayList<>());
        assertThat(maxRatioStrategy.getCandidates()).isEmpty();
    }

    @Test
    void getCandidates() {
        maxRatioStrategy.updateCandidates(candidatesForMaxRatio);

        final List<Endpoint> selectedCandidates = maxRatioStrategy.getCandidates();
        assertThat(selectedCandidates).hasSize(9);

        selectedCandidates.forEach(
                selectedCandidate -> assertThat(candidatesForMaxRatio).contains(selectedCandidate));

        assertUniqueCandidates(selectedCandidates);
    }

    @Test
    void updateHealthWhenEndpointIsHealthy() {
        final Endpoint endpoint = candidatesForMaxRatio.get(1);

        assertThat(maxRatioStrategy.updateHealth(endpoint, HEALTHY)).isFalse();
    }

    @Test
    void updateHealthWhenEndpointIsUnhealthyOnMaxRatioMode() {
        final Endpoint unhealthyEndpoint = maxRatioStrategy.getCandidates().get(0);

        assertThat(maxRatioStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY)).isTrue();

        final List<Endpoint> selectedCandidates = maxRatioStrategy.getCandidates();
        assertThat(selectedCandidates).hasSize(9)
                                      .doesNotContain(unhealthyEndpoint);

        selectedCandidates.forEach(
                selectedCandidate -> assertThat(candidatesForMaxRatio).contains(selectedCandidate));

        assertUniqueCandidates(selectedCandidates);
    }

    @Test
    void updateHealthWhenEndpointIsUnhealthyOnMaxValueMode() {
        final Endpoint unhealthyEndpoint = maxCountStrategy.getCandidates().get(0);

        assertThat(maxCountStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY)).isTrue();

        final List<Endpoint> selectedCandidates = maxCountStrategy.getCandidates();
        assertThat(selectedCandidates).hasSize(5)
                                      .doesNotContain(unhealthyEndpoint);

        selectedCandidates.forEach(
                selectedCandidate -> assertThat(candidatesForMaxCount).contains(selectedCandidate));
    }

    @Test
    void updateHealthWhenEndpointIsUnhealthyButDoesNotHaveEnoughCandidatesOnMaxRatioMode() {
        final List<Endpoint> endpoints = createCandidates(5);

        maxRatioStrategy = new PartialHealthCheckStrategyBuilder().maxEndpointRatio(1).build();
        maxRatioStrategy.updateCandidates(endpoints);

        for (Endpoint unhealthyEndpoint : maxRatioStrategy.getCandidates()) {
            final boolean updateRes = maxRatioStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY);

            final List<Endpoint> selectedCandidates = maxRatioStrategy.getCandidates();
            // When there are not enough candidates, some of the unhealthy candidates are chosen again.
            // At this time, even an unhealthy candidate delivered by the function may be randomly chosen again.
            if (selectedCandidates.contains(unhealthyEndpoint)) {
                assertThat(updateRes).isFalse();
            } else {
                assertThat(updateRes).isTrue();
            }

            assertThat(selectedCandidates).hasSize(5);
            selectedCandidates.forEach(
                    selectedCandidate -> assertThat(endpoints).contains(selectedCandidate));
        }
    }

    @Test
    void updateHealthWhenEndpointIsUnhealthyButDoesNotHaveEnoughCandidatesOnMaxValueMode() {
        for (Endpoint unhealthyEndpoint : maxCountStrategy.getCandidates()) {
            final boolean updateRes = maxCountStrategy.updateHealth(unhealthyEndpoint, UNHEALTHY);

            final List<Endpoint> selectedCandidates = maxCountStrategy.getCandidates();
            // When there are not enough candidates, some of the unhealthy candidates are chosen again.
            // At this time, even an unhealthy candidate delivered by the function may be randomly chosen again.
            if (selectedCandidates.contains(unhealthyEndpoint)) {
                assertThat(updateRes).isFalse();
            } else {
                assertThat(updateRes).isTrue();
            }

            assertThat(selectedCandidates).hasSize(5);
            selectedCandidates.forEach(
                    selectedCandidate -> assertThat(candidatesForMaxCount).contains(selectedCandidate));
        }
    }

    @Test
    void updateHealthWhenMaxRatioMode() {
        List<Endpoint> selectedCandidates = maxRatioStrategy.getCandidates();
        final Endpoint unhealthyCandidate = selectedCandidates.get(0);

        assertThat(selectedCandidates).hasSize(9);

        boolean updateRes = maxRatioStrategy.updateHealth(unhealthyCandidate, UNHEALTHY);
        selectedCandidates = maxRatioStrategy.getCandidates();

        assertThat(updateRes).isTrue();
        assertThat(selectedCandidates).hasSize(9)
                                      .doesNotContain(unhealthyCandidate);

        updateRes = maxRatioStrategy.updateHealth(unhealthyCandidate, HEALTHY);
        selectedCandidates = maxRatioStrategy.getCandidates();

        assertThat(updateRes).isFalse();
        assertThat(selectedCandidates).hasSize(9)
                                      .doesNotContain(unhealthyCandidate);

        updateRes = maxRatioStrategy.updateHealth(selectedCandidates.get(0), UNHEALTHY);
        selectedCandidates = maxRatioStrategy.getCandidates();

        assertThat(updateRes).isTrue();
        assertThat(selectedCandidates).hasSize(9)
                                      .contains(unhealthyCandidate);
    }

    @Test
    void updateHealthWhenMaxValueMode() {
        List<Endpoint> selectedCandidates = maxCountStrategy.getCandidates();
        final Endpoint unhealthyCandidate = selectedCandidates.get(0);

        assertThat(selectedCandidates).hasSize(5);

        boolean updateRes = maxCountStrategy.updateHealth(unhealthyCandidate, UNHEALTHY);
        selectedCandidates = maxCountStrategy.getCandidates();

        assertThat(updateRes).isTrue();
        assertThat(selectedCandidates).hasSize(5)
                                      .doesNotContain(unhealthyCandidate);

        updateRes = maxCountStrategy.updateHealth(unhealthyCandidate, HEALTHY);
        selectedCandidates = maxCountStrategy.getCandidates();

        assertThat(updateRes).isFalse();
        assertThat(selectedCandidates).hasSize(5)
                                      .doesNotContain(unhealthyCandidate);

        updateRes = maxCountStrategy.updateHealth(selectedCandidates.get(0), UNHEALTHY);
        selectedCandidates = maxCountStrategy.getCandidates();

        assertThat(updateRes).isTrue();
        assertThat(selectedCandidates).hasSize(5)
                                      .contains(unhealthyCandidate);
    }

    @Test
    void updateHealthByDisappearedCandidate() {
        final Endpoint disappearedCandidate = Endpoint.of("disappeared");
        final List<Endpoint> candidates = createCandidates(3);

        maxCountStrategy.updateCandidates(candidates);
        assertThat(maxCountStrategy.getCandidates()).hasSize(3);

        boolean updateRes = maxCountStrategy.updateHealth(disappearedCandidate, HEALTHY);
        assertThat(updateRes).isTrue();

        List<Endpoint> selectedCandidates = maxCountStrategy.getCandidates();
        assertThat(selectedCandidates).hasSize(3)
                                      .doesNotContain(disappearedCandidate);

        updateRes = maxCountStrategy.updateHealth(disappearedCandidate, UNHEALTHY);
        assertThat(updateRes).isTrue();

        selectedCandidates = maxCountStrategy.getCandidates();
        assertThat(selectedCandidates).hasSize(3)
                                      .doesNotContain(disappearedCandidate);
    }

    @Test
    void updateCandidates() {
        final List<Endpoint> newCandidates = createCandidates(5);
        maxCountStrategy.updateCandidates(newCandidates);
        assertCandidates(maxCountStrategy.getCandidates(), newCandidates);

        final List<Endpoint> someOfOldCandidates = candidatesForMaxCount.subList(0, 3);
        maxCountStrategy.updateCandidates(someOfOldCandidates);
        assertCandidates(maxCountStrategy.getCandidates(), someOfOldCandidates);

        final List<Endpoint> mixedCandidates = Streams.concat(createCandidates(2).stream(),
                                                              someOfOldCandidates.stream())
                                                      .collect(toImmutableList());
        maxCountStrategy.updateCandidates(mixedCandidates);
        assertCandidates(maxCountStrategy.getCandidates(), mixedCandidates);
    }
}
