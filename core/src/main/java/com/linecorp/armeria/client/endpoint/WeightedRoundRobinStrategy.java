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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
     *   <li>if endpoint weights are 1,1,2,3 (or 2,2,4,6), then select result is abcdcdd (or abcdabcdcdcddd) ...</li>
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

            /**
             * In general, assume the weights are w0 < w1 < ... < wM where M = N - 1, N is number of endpoints.
             * <p>
             * <ul>
             *   <li>The first part of result: (a0..aM)(a0..aM)...(a0..aM) [w0 times for N elements].</li>
             *   <li>The second part of result: (a1..aM)...(a1..aM) [w1 - w0 times for N - 1 elements].</li>
             *   <li>The third part of result: (a2..aM)...(a2..aM) [w2 - w1 times for N - 2 elements].</li>
             *   <li>...</li>
             * </ul>
             * <p>In this way:
             * <ul>
             *   <li>Total number of elements of first part is: X0 = w0 * N</li>
             *   <li>Total number of elements of second part is: X1 = (w1 - w0) * (N - 1)</li>
             *   <li>Total number of elements of third part is: X2 = (w2 - w1) * (N - 2)</li>
             *   <li>...</li>
             * </ul>
             * <p>
             * Therefore, to find index of endpoint for a sequence S = current_sequence % total_weight, we could find
             * the part which sequence belongs first, and then modular by the number of elements in this part for real index.
             * <p>
             * Let F denote accumulation function:
             * <ul>
             *   <li>F(0) = X0</li>
             *   <li>F(1) = X0 + X1</li>
             *   <li>F(2) = X0 + X1 + X2</li>
             *   <li>...</li>
             * </ul>
             * Note: X0 X1 ... are all positive.
             * <p>
             * We could easily find the part (which sequence belongs) by binary search on F.
             * Just find the index k where: F(k) <= S < F(k + 1)
             * <p>
             * So, S belongs to part number (k + 1), index_in_this_part = S - F(k).
             * If we are able to map index_in_this_part to real_index of endpoints (w0..wM), then we get final result.
             * <p>
             * The formula is: real_index = (k + 1) + (index_in_this_part % (N - k - 1))
             * Proven: the part number (k + 1) start at index (k + 1), and contains (N - k - 1) elements.
             * <p>
             * For special case like wi == w(i+1). We just group them all together and mark the start index of the group.
             * <p>
             * The complexity of selecting endpoint is: O(log(N))
             */
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
                List<Endpoint> _endpoints = ImmutableList.copyOf(endpoints)
                        .stream()
                        .filter(endpoint -> endpoint.weight() > 0) // only process endpoint with weight > 0
                        .sorted(Comparator
                                .comparing(Endpoint::weight)
                                .thenComparing(Endpoint::host)
                                .thenComparingInt(Endpoint::port))
                        .collect(Collectors.toList());
                int numEndpoints = _endpoints.size();

                // accumulation
                LinkedList<EndpointsGroupByWeight> accumulatedGroups = new LinkedList<>();
                EndpointsGroupByWeight currentGroup = null;
                int rest = numEndpoints;
                for (Endpoint endpoint : _endpoints) {
                    if (currentGroup == null || currentGroup.weight != endpoint.weight()) {
                        totalWeight += currentGroup == null ?
                                (long) endpoint.weight() * (long) rest
                                : (long) (endpoint.weight() - currentGroup.weight) * (long) rest;
                        currentGroup = new EndpointsGroupByWeight(numEndpoints - rest, endpoint.weight(), totalWeight);
                        accumulatedGroups.addLast(currentGroup);
                    }

                    rest--;
                }

                this.endpoints = _endpoints;
                this.endpointsGroupByWeight = accumulatedGroups.toArray(new EndpointsGroupByWeight[accumulatedGroups.size()]);
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

                    if (mod < endpointsGroupByWeight[0].accumulatedWeight)
                        return endpoints.get((int) (mod % numberEndpoints));

                    int left = 0, right = endpointsGroupByWeight.length - 1, mid;
                    while (left < right) {
                        mid = left + ((right - left) >> 1);

                        if (mid == left)
                            break;

                        if (endpointsGroupByWeight[mid].accumulatedWeight <= mod)
                            left = mid;
                        else
                            right = mid;
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