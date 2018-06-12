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
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;

@State(Scope.Thread)
public class WeightedRoundRobinStrategyBenchmark {

    final int numEndpoints = 500;

    // normal round robin, all weight: 300
    EndpointSelector selectorSameWeight;

    // mainly weight: 1, max weight: 30
    EndpointSelector selectorRandomMainly1Max30;

    // randomly, max weight: 10
    EndpointSelector selectorRandomMax10;

    // randomly, max weight: 100
    EndpointSelector selectorRandomMax100;

    // all weights are unique
    EndpointSelector selectorUnique;

    interface EndpointGenerator {
        Endpoint generate(int id);
    }

    private List<Endpoint> generateEndpoints(EndpointGenerator e) {
        List<Endpoint> result = new ArrayList<>();
        for (int i = 0; i < numEndpoints; i++) {
            result.add(e.generate(i));
        }
        return result;
    }

    private EndpointSelector getEndpointSelector(List<Endpoint> endpoints, String groupName) {
        EndpointGroupRegistry.register(groupName,
                new StaticEndpointGroup(endpoints),
                EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);
        return EndpointGroupRegistry.getNodeSelector(groupName);
    }

    @Setup
    public void setupCases() {
        Random rand = new Random();

        selectorSameWeight = getEndpointSelector(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1)
                        .withWeight(
                                300
                        )), "same-weight");

        selectorRandomMainly1Max30 = getEndpointSelector(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1).withWeight(
                        1 + (id % 50 == 0 ? 29 : 0)
                )), "main-1-max-30");

        selectorRandomMax10 = getEndpointSelector(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1).withWeight(
                        1 + rand.nextInt(10)
                )), "random-max-10");

        selectorRandomMax100 = getEndpointSelector(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1).withWeight(
                        1 + rand.nextInt(100)
                )), "random-max-100");

        selectorUnique = getEndpointSelector(generateEndpoints(
                id -> Endpoint.of("127.0.0.1", id + 1).withWeight(
                        id + 1
                )), "unique");
    }

    @Benchmark
    public Endpoint sameWeight() throws Exception {
        return selectorSameWeight.select(null);
    }

    @Benchmark
    public Endpoint randomMainly1Max30() throws Exception {
        return selectorRandomMainly1Max30.select(null);
    }

    @Benchmark
    public Endpoint randomMax10() throws Exception {
        return selectorRandomMax10.select(null);
    }

    @Benchmark
    public Endpoint randomMax100() throws Exception {
        return selectorRandomMax100.select(null);
    }

    @Benchmark
    public Endpoint unique() throws Exception {
        return selectorUnique.select(null);
    }
}
