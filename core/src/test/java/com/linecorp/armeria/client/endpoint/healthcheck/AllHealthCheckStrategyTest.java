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

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;

public class AllHealthCheckStrategyTest {

    private HealthCheckStrategy strategy;
    private List<Endpoint> candidates;

    private static List<Endpoint> createCandidates(int size) {
        final Random random = new Random();

        return IntStream.range(0, size)
                        .mapToObj(i -> Endpoint.ofGroup("dummy" + random.nextInt()))
                        .collect(Collectors.toList());
    }

    @BeforeEach
    void beforeEach() {
        strategy = new AllHealthCheckStrategy();

        candidates = createCandidates(10);
    }

    @Test
    void getCandidatesWhenBeforeFirstUpdateCandidates() {
        assertThat(strategy.getCandidates()).isEmpty();
    }

    @Test
    void updateAndGetCandidates() {
        strategy.updateCandidates(candidates);
        List<Endpoint> actCandidates = strategy.getCandidates();
        assertCandidates(actCandidates, candidates);

        final List<Endpoint> anotherCandidates = createCandidates(15);
        anotherCandidates.addAll(candidates);

        strategy.updateCandidates(anotherCandidates);
        actCandidates = strategy.getCandidates();
        assertCandidates(actCandidates, anotherCandidates);
    }

    @Test
    void updateHealthWhenEndpointHealthyAndUnhealthy() {
        strategy.updateCandidates(candidates);

        final Endpoint candidate = candidates.get(0);
        boolean actUpdateRes = strategy.updateHealth(candidate, 0);
        List<Endpoint> actCandidates = strategy.getCandidates();

        assertThat(actUpdateRes).isFalse();
        assertCandidates(actCandidates, candidates);

        actUpdateRes = strategy.updateHealth(candidate, 1);
        actCandidates = strategy.getCandidates();

        assertThat(actUpdateRes).isFalse();
        assertCandidates(actCandidates, candidates);
    }

    @Test
    void updateHealthByDisappearedCandidate() {
        strategy.updateCandidates(candidates);
        final Endpoint disappearedCandidate = Endpoint.ofGroup("dummy");

        boolean actUpdateRes = strategy.updateHealth(disappearedCandidate, 0);
        assertThat(actUpdateRes).isTrue();
        assertCandidates(strategy.getCandidates(), candidates);

        actUpdateRes = strategy.updateHealth(disappearedCandidate, 1);
        assertThat(actUpdateRes).isTrue();
        assertCandidates(strategy.getCandidates(), candidates);
    }

    private void assertCandidates(List<Endpoint> act, List<Endpoint> exp) {
        assertThat(act).hasSize(exp.size());

        for (Endpoint expCandidate : exp) {
            assertThat(act.contains(expCandidate)).isTrue();
        }
    }
}