/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;

final class WeightedRoundRobinStrategy implements EndpointSelectionStrategy {

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new WeightedRoundRobinSelector(endpointGroup);
    }

    /**
     * A weighted round robin select strategy.
     *
     * <p>For example, with node a, b and c:
     * <ul>
     *   <li>if endpoint weights are 1,1,1 (or 2,2,2), then select result is abc abc ...</li>
     *   <li>if endpoint weights are 1,2,3 (or 2,4,6), then select result is abcbcc(or abcabcbcbccc) ...</li>
     *   <li>if endpoint weights are 3,5,7, then select result is abcabcabcbcbcbb abcabcabcbcbcbb ...</li>
     * </ul>
     */
    private static final class WeightedRoundRobinSelector implements EndpointSelector {
        private final EndpointGroup endpointGroup;
        private final AtomicInteger sequence = new AtomicInteger();
        private volatile EndpointsAndWeights endpointsAndWeights;

        WeightedRoundRobinSelector(EndpointGroup endpointGroup) {
            this.endpointGroup = endpointGroup;
            endpointsAndWeights = new EndpointsAndWeights(endpointGroup.endpoints());
            endpointGroup.addListener(endpoints -> endpointsAndWeights = new EndpointsAndWeights(endpoints));
        }

        @Override
        public EndpointGroup group() {
            return endpointGroup;
        }

        @Override
        public EndpointSelectionStrategy strategy() {
            return WEIGHTED_ROUND_ROBIN;
        }

        @Override
        public Endpoint select(ClientRequestContext ctx) {
            int currentSequence = sequence.getAndIncrement();
            return endpointsAndWeights.selectEndpoint(currentSequence);
        }

        private static final class EndpointsAndWeights {
            private final List<Endpoint> endpoints;
            private final boolean weighted;
            private final int maxWeight;
            private final int totalWeight;

            EndpointsAndWeights(Iterable<Endpoint> endpoints) {
                int minWeight = Integer.MAX_VALUE;
                int maxWeight = Integer.MIN_VALUE;
                int totalWeight = 0;
                for (Endpoint endpoint : endpoints) {
                    int weight = endpoint.weight();
                    minWeight = Math.min(minWeight, weight);
                    maxWeight = Math.max(maxWeight, weight);
                    totalWeight += weight;
                }
                this.endpoints = ImmutableList.copyOf(endpoints);
                this.maxWeight = maxWeight;
                this.totalWeight = totalWeight;
                weighted = minWeight != maxWeight;
            }

            Endpoint selectEndpoint(int currentSequence) {
                if (endpoints.isEmpty()) {
                    throw new EndpointGroupException(endpoints + " is empty");
                }

                if (weighted) {
                    int[] weights = endpoints.stream()
                                             .mapToInt(Endpoint::weight)
                                             .toArray();

                    int mod = currentSequence % totalWeight;
                    for (int i = 0; i < maxWeight; i++) {
                        for (int j = 0; j < weights.length; j++) {
                            if (mod == 0 && weights[j] > 0) {
                                return endpoints.get(j);
                            }
                            if (weights[j] > 0) {
                                weights[j]--;
                                mod--;
                            }
                        }
                    }
                }
                return endpoints.get(Math.abs(currentSequence % endpoints.size()));
            }
        }
    }
}
