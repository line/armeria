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

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
            final int currentSequence = sequence.getAndIncrement();
            return endpointsAndWeights.selectEndpoint(currentSequence);
        }

        private static final class EndpointsAndWeights {
            private final List<Endpoint> endpoints;
            private final boolean weighted;
            private final long totalWeight; // prevent overflow by using long

            private static final class EndpointsGroupByWeight {
                int startIndex;
                int weight;
                long accumulatedWeight;

                EndpointsGroupByWeight(int startIndex, int weight, long accumulatedWeight) {
                    this.startIndex = startIndex;
                    this.weight = weight;
                    this.accumulatedWeight = accumulatedWeight;
                }
            }

            private final EndpointsGroupByWeight[] endpointsGroupByWeight;

            EndpointsAndWeights(Iterable<Endpoint> endpoints) {
                int minWeight = Integer.MAX_VALUE;
                int maxWeight = Integer.MIN_VALUE;
                long totalWeight = 0;

                // get min and max weight
                for (Endpoint endpoint : endpoints) {
                    final int weight = endpoint.weight();
                    minWeight = Math.min(minWeight, weight);
                    maxWeight = Math.max(maxWeight, weight);
                }

                // prepare endpoints
                List<Endpoint> endps = ImmutableList.copyOf(endpoints)
                        .stream()
                        .filter(endpoint -> endpoint.weight() > 0) // only process endpoint with weight > 0
                        .sorted(Comparator
                                .comparing(Endpoint::weight)
                                .thenComparing(Endpoint::host)
                                .thenComparingInt(Endpoint::port))
                        .collect(Collectors.toList());
                int numEndpoints = endps.size();

                // accumulation
                LinkedList<EndpointsGroupByWeight> accumulatedGroups = new LinkedList<>();
                EndpointsGroupByWeight currentGroup = null;
                int rest = numEndpoints;
                for (Endpoint endpoint : endps) {
                    if (currentGroup == null || currentGroup.weight != endpoint.weight()) {
                        totalWeight += currentGroup == null ?
                                (long) endpoint.weight() * (long) rest
                                : (long) (endpoint.weight() - currentGroup.weight) * (long) rest;
                        currentGroup = new EndpointsGroupByWeight(numEndpoints - rest,
                                endpoint.weight(), totalWeight);
                        accumulatedGroups.addLast(currentGroup);
                    }

                    rest--;
                }

                this.endpoints = endps;
                this.endpointsGroupByWeight = accumulatedGroups.toArray(
                        new EndpointsGroupByWeight[accumulatedGroups.size()]
                );
                this.totalWeight = totalWeight;
                this.weighted = minWeight != maxWeight;
            }

            Endpoint selectEndpoint(int currentSequence) {
                if (endpoints.isEmpty()) {
                    throw new EndpointGroupException(endpoints + " is empty");
                }

                int numberEndpoints = endpoints.size();

                if (weighted) {
                    long mod = Math.abs((long) (currentSequence) % totalWeight);

                    if (mod < endpointsGroupByWeight[0].accumulatedWeight) {
                        return endpoints.get((int) (mod % numberEndpoints));
                    }

                    int left = 0;
                    int right = endpointsGroupByWeight.length - 1;
                    int mid;
                    while (left < right) {
                        mid = left + ((right - left) >> 1);

                        if (mid == left) {
                            break;
                        }

                        if (endpointsGroupByWeight[mid].accumulatedWeight <= mod) {
                            left = mid;
                        } else {
                            right = mid;
                        }
                    }

                    // (left + 1) is the part where sequence belongs
                    int indexInPart = (int) (mod - endpointsGroupByWeight[left].accumulatedWeight);
                    int realIndex = endpointsGroupByWeight[left + 1].startIndex +
                            indexInPart % (numberEndpoints - endpointsGroupByWeight[left + 1].startIndex);
                    return endpoints.get(realIndex);
                }

                return endpoints.get(Math.abs(currentSequence % numberEndpoints));
            }
        }
    }
}
