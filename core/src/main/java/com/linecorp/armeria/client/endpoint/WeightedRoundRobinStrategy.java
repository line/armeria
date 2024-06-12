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

package com.linecorp.armeria.client.endpoint;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

final class WeightedRoundRobinStrategy implements EndpointSelectionStrategy {

    static final WeightedRoundRobinStrategy INSTANCE = new WeightedRoundRobinStrategy();

    private WeightedRoundRobinStrategy() {}

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
     *   <li>if endpoint weights are 3,5,7, then select result is abcabcabcbcbccc abcabcabcbcbccc ...</li>
     * </ul>
     */
    private static final class WeightedRoundRobinSelector extends AbstractEndpointSelector {

        private final AtomicInteger sequence = new AtomicInteger();
        @Nullable
        private volatile EndpointsAndWeights endpointsAndWeights;

        WeightedRoundRobinSelector(EndpointGroup endpointGroup) {
            super(endpointGroup);
            initialize();
        }

        @Override
        protected CompletableFuture<Void> updateNewEndpoints(List<Endpoint> endpoints) {
            final EndpointsAndWeights endpointsAndWeights = this.endpointsAndWeights;
            if (endpointsAndWeights == null || endpointsAndWeights.endpoints != endpoints) {
                this.endpointsAndWeights = new EndpointsAndWeights(endpoints);
            }
            return UnmodifiableFuture.completedFuture(null);
        }

        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            final EndpointsAndWeights endpointsAndWeights = this.endpointsAndWeights;
            if (endpointsAndWeights == null) {
                // 'endpointGroup' has not been initialized yet.
                return null;
            }
            final int currentSequence = sequence.getAndIncrement();
            return endpointsAndWeights.selectEndpoint(currentSequence);
        }

        // endpoints accumulation which are grouped by weight
        private static final class EndpointsGroupByWeight {
            final long startIndex;
            final int weight;
            final long accumulatedWeight;

            EndpointsGroupByWeight(long startIndex, int weight, long accumulatedWeight) {
                this.startIndex = startIndex;
                this.weight = weight;
                this.accumulatedWeight = accumulatedWeight;
            }
        }

        //
        // In general, assume the weights are w0 < w1 < ... < wM where M = N - 1, N is number of endpoints.
        //
        // * The first part of result: (a0..aM)(a0..aM)...(a0..aM) [w0 times for N elements].
        // * The second part of result: (a1..aM)...(a1..aM) [w1 - w0 times for N - 1 elements].
        // * and so on
        //
        // In this way:
        //
        // * Total number of elements of first part is: X(0) = w0 * N.
        // * Total number of elements of second part is: X(1) = (w1 - w0) * (N - 1)
        // * and so on
        //
        // Therefore, to find endpoint for a sequence S = currentSequence % totalWeight, firstly we find
        // the part which sequence belongs, and then modular by the number of elements in this part.
        //
        // Accumulation function F:
        //
        // * F(0) = X(0)
        // * F(1) = X(0) + X(1)
        // * F(2) = X(0) + X(1) + X(2)
        // * F(i) = F(i-1) + X(i)
        //
        // We could easily find the part (which sequence S belongs) using binary search on F.
        // Just find the index k where:
        //
        //                               F(k) <= S < F(k + 1).
        //
        // So, S belongs to part number (k + 1), index of the sequence in this part is P = S - F(k).
        // Because part (k + 1) start at index (k + 1), and contains (N - k - 1) elements,
        // then the real index is:
        //
        //                              (k + 1) + (P % (N - k - 1))
        //
        // For special case like w(i) == w(i+1). We just group them all together
        // and mark the start index of the group.
        //
        private static final class EndpointsAndWeights {
            private final List<Endpoint> endpoints;
            private final boolean weighted;
            private final long totalWeight; // prevent overflow by using long
            private final List<EndpointsGroupByWeight> accumulatedGroups;

            EndpointsAndWeights(Iterable<Endpoint> endpoints) {

                // prepare immutable endpoints
                this.endpoints = Streams.stream(endpoints)
                                        .filter(e -> e.weight() > 0) // only process endpoint with weight > 0
                                        .sorted(Comparator.comparing(Endpoint::weight)
                                                          .thenComparing(Endpoint::host)
                                                          .thenComparingInt(Endpoint::port))
                                        .collect(toImmutableList());
                final long numEndpoints = this.endpoints.size();

                // get min weight, max weight and number of distinct weight
                int minWeight = Integer.MAX_VALUE;
                int maxWeight = Integer.MIN_VALUE;
                int numberDistinctWeight = 0;

                int oldWeight = -1;
                for (Endpoint endpoint : this.endpoints) {
                    final int weight = endpoint.weight();
                    minWeight = Math.min(minWeight, weight);
                    maxWeight = Math.max(maxWeight, weight);
                    numberDistinctWeight += weight == oldWeight ? 0 : 1;
                    oldWeight = weight;
                }

                // accumulation
                long totalWeight = 0;

                final ImmutableList.Builder<EndpointsGroupByWeight> accumulatedGroupsBuilder =
                        ImmutableList.builderWithExpectedSize(numberDistinctWeight);
                EndpointsGroupByWeight currentGroup = null;

                long rest = numEndpoints;
                for (Endpoint endpoint : this.endpoints) {
                    if (currentGroup == null || currentGroup.weight != endpoint.weight()) {
                        totalWeight += currentGroup == null ? endpoint.weight() * rest
                                                            : (endpoint.weight() - currentGroup.weight) * rest;
                        currentGroup = new EndpointsGroupByWeight(
                                numEndpoints - rest, endpoint.weight(), totalWeight
                        );
                        accumulatedGroupsBuilder.add(currentGroup);
                    }

                    rest--;
                }

                accumulatedGroups = accumulatedGroupsBuilder.build();
                this.totalWeight = totalWeight;
                weighted = minWeight != maxWeight;
            }

            @Nullable
            Endpoint selectEndpoint(int currentSequence) {
                if (endpoints.isEmpty()) {
                    return null;
                }

                if (weighted) {
                    final long numberEndpoints = endpoints.size();

                    final long mod = Math.abs(currentSequence % totalWeight);

                    if (mod < accumulatedGroups.get(0).accumulatedWeight) {
                        return endpoints.get((int) (mod % numberEndpoints));
                    }

                    int left = 0;
                    int right = accumulatedGroups.size() - 1;
                    int mid;
                    while (left < right) {
                        mid = left + ((right - left) >> 1);

                        if (mid == left) {
                            break;
                        }

                        if (accumulatedGroups.get(mid).accumulatedWeight <= mod) {
                            left = mid;
                        } else {
                            right = mid;
                        }
                    }

                    // (left + 1) is the part where sequence belongs
                    final long indexInPart = mod - accumulatedGroups.get(left).accumulatedWeight;
                    final long startIndex = accumulatedGroups.get(left + 1).startIndex;
                    return endpoints.get((int) (startIndex + indexInPart % (numberEndpoints - startIndex)));
                }

                return endpoints.get(Math.abs(currentSequence % endpoints.size()));
            }
        }
    }
}
