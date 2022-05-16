/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.core.client.endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Microbenchmarks of different {@link EndpointSelector} configurations.
 */
@State(Scope.Thread)
public class WeightedRoundRobinStrategyBenchmark {

    private static final int numEndpoints = 500;

    // normal round robin, all weight: 300
    EndpointGroup groupSameWeight;

    // mainly weight: 1, max weight: 30
    EndpointGroup groupRandomMainly1Max30;

    // randomly, max weight: 10
    EndpointGroup groupRandomMax10;

    // randomly, max weight: 100
    EndpointGroup groupRandomMax100;

    // all weights are unique
    EndpointGroup groupUnique;

    interface EndpointGenerator {
        Endpoint generate(int id);
    }

    private static List<Endpoint> generateEndpoints(EndpointGenerator e) {
        final List<Endpoint> result = new ArrayList<>();
        for (int i = 0; i < numEndpoints; i++) {
            result.add(e.generate(i));
        }
        return result;
    }

    private static EndpointGroup getEndpointGroup(List<Endpoint> endpoints) {
        return EndpointGroup.of(EndpointSelectionStrategy.weightedRoundRobin(), endpoints);
    }

    @Setup
    public void setupCases() {
        final Random rand = new Random();

        groupSameWeight = getEndpointGroup(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1)
                              .withWeight(300)));

        groupRandomMainly1Max30 = getEndpointGroup(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1)
                              .withWeight(1 + (id % 50 == 0 ? 29 : 0))));

        groupRandomMax10 = getEndpointGroup(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1)
                              .withWeight(1 + rand.nextInt(10))));

        groupRandomMax100 = getEndpointGroup(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1)
                              .withWeight(1 + rand.nextInt(100))));

        groupUnique = getEndpointGroup(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1)
                              .withWeight(id + 1)));
    }

    @Nullable
    @Benchmark
    public Endpoint sameWeight() throws Exception {
        return groupSameWeight.selectNow(null);
    }

    @Nullable
    @Benchmark
    public Endpoint randomMainly1Max30() throws Exception {
        return groupRandomMainly1Max30.selectNow(null);
    }

    @Nullable
    @Benchmark
    public Endpoint randomMax10() throws Exception {
        return groupRandomMax10.selectNow(null);
    }

    @Nullable
    @Benchmark
    public Endpoint randomMax100() throws Exception {
        return groupRandomMax100.selectNow(null);
    }

    @Nullable
    @Benchmark
    public Endpoint unique() throws Exception {
        return groupUnique.selectNow(null);
    }
}
