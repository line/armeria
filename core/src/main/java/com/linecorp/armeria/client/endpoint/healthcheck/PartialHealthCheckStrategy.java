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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

/**
 * A strategy to check part of candidates by health.
 */
final class PartialHealthCheckStrategy implements HealthCheckStrategy {

    /**
     * Creates a new builder.
     */
    static PartialHealthCheckStrategyBuilder builder() {
        return new PartialHealthCheckStrategyBuilder();
    }

    private final EndpointLimitingFunction endpointLimitingFunction;

    /**
     * Creates a new instance.
     */
    PartialHealthCheckStrategy(EndpointLimitingFunction endpointLimitingFunction) {
        this.endpointLimitingFunction = requireNonNull(endpointLimitingFunction, "endpointLimitingFunction");
    }

    @Override
    public List<Endpoint> select(List<Endpoint> candidates) {
        candidates = candidates.stream().distinct().collect(toImmutableList());
        final int numCandidates = candidates.size();
        final int numEndpoints = endpointLimitingFunction.calculate(numCandidates);
        if (numCandidates == numEndpoints) {
            return candidates;
        }
        return sampleEndpoints(candidates, numEndpoints);
    }

    private static List<Endpoint> sampleEndpoints(List<Endpoint> candidates, int numSamples) {
        final Random random = ThreadLocalRandom.current();
        final ImmutableList.Builder<Endpoint> builder = ImmutableList.builderWithExpectedSize(numSamples);
        final int numCandidates = candidates.size();
        for (int i = 0; numSamples > 0; i++) {
            final int rand = random.nextInt(numCandidates - i);
            if (rand < numSamples) {
                builder.add(candidates.get(i));
                numSamples--;
            }
        }
        return builder.build();
    }

    static final class EndpointLimitingFunction {

        private final int count;
        private final double ratio;
        private final boolean ratioMode;

        private EndpointLimitingFunction(int count, double ratio, boolean ratioMode) {
            this.count = count;
            this.ratio = ratio;
            this.ratioMode = ratioMode;
        }

        static EndpointLimitingFunction ofCount(int count) {
            checkArgument(count > 0, "count: %s (expected: 0 < count <= MAX_INT)", count);
            return new EndpointLimitingFunction(count, 0, false);
        }

        static EndpointLimitingFunction ofRatio(double ratio) {
            checkArgument(0 < ratio && ratio <= 1, "ratio: %s (expected: 0 < ratio <= 1)",
                          ratio);

            return new EndpointLimitingFunction(0, ratio, true);
        }

        int calculate(int numCandidates) {
            if (ratioMode) {
                return Math.max(1, (int) (numCandidates * ratio));
            } else {
                return Math.min(count, numCandidates);
            }
        }
    }
}
