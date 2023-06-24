/*
 * Copyright 2023 LINE Corporation
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.hash.Hashing;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;

final class RingHashEndpointSelectionStrategy implements EndpointSelectionStrategy {

    static final RingHashEndpointSelectionStrategy INSTANCE = new RingHashEndpointSelectionStrategy();

    private RingHashEndpointSelectionStrategy() {}

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new RingHashSelector(endpointGroup);
    }

    public EndpointSelector newSelector(EndpointGroup endpointGroup, int size) {
        return new RingHashSelector(endpointGroup, size);
    }

    static class RingHashSelector extends AbstractEndpointSelector {

        @Nullable
        private volatile WeightedRingEndpoint weightedRingEndpoint;

        private final AtomicInteger sequence = new AtomicInteger();

        /**
         * A Ring hash select strategy.
         */
        RingHashSelector(EndpointGroup endpointGroup) {
            super(endpointGroup);
            endpointGroup.addListener(endpoints -> weightedRingEndpoint = new WeightedRingEndpoint(endpoints),
                                      true);
        }

        RingHashSelector(EndpointGroup endpointGroup, int size) {
            super(endpointGroup);
            endpointGroup.addListener(endpoints -> weightedRingEndpoint = new WeightedRingEndpoint(endpoints, size),
                                      true);
        }

        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            final WeightedRingEndpoint weightedRingEndpoint = this.weightedRingEndpoint;
            if (weightedRingEndpoint == null) {
                // 'endpointGroup' has not been initialized yet.
                return null;
            }

            final int currentSequence = sequence.getAndIncrement();
            return weightedRingEndpoint.select(currentSequence);
        }

        private final class WeightedRingEndpoint {
            private final Int2ObjectSortedMap<Endpoint> ring = new Int2ObjectAVLTreeMap<>();
            private final List<Endpoint> endpoints;

            @Nullable
            Endpoint select(int sequence) {
                if (endpoints.isEmpty()) {
                    return null;
                }

                final String sequenceString = String.valueOf(sequence);
                final int key = getXXHash(sequenceString);
                final SortedMap<Integer, Endpoint> tailMap = ring.tailMap(key);
                return tailMap.isEmpty() ? ring.get(ring.firstIntKey()) : tailMap.get(tailMap.firstKey());
            }

            WeightedRingEndpoint(List<Endpoint> endpoints) {
                this(endpoints, endpoints.stream()
                                         .filter(e -> e.weight() > 0) // only process endpoint with weight > 0
                                         .sorted(Comparator.comparing(Endpoint::weight)
                                                           .thenComparing(Endpoint::host)
                                                           .thenComparingInt(Endpoint::port))
                                         .collect(toImmutableList()).size());
            }

            WeightedRingEndpoint(List<Endpoint> endpoints, int size) {
                this.endpoints = endpoints.stream()
                                          .filter(e -> e.weight() > 0) // only process endpoint with weight > 0
                                          .sorted(Comparator.comparing(Endpoint::weight)
                                                            .thenComparing(Endpoint::host)
                                                            .thenComparingInt(Endpoint::port))
                                          .collect(toImmutableList());
                final int sizeOfEndpoints = this.endpoints.size();
                assert sizeOfEndpoints >= size;

                final List<Integer> arr = new ArrayList<>();
                for (Endpoint endpoint : this.endpoints) {
                    final int weight = endpoint.weight();
                    arr.add(weight);
                }
                final int divider = binarySearch(arr, size);
                for (Endpoint endpoint : this.endpoints) {
                    final int weight = endpoint.weight();
                    final String host = endpoint.host();
                    // If weight is 3 and x is 1, place 3 times in the ring
                    // if weight is 1 and x is 1, place once in the ring
                    // If weight is 3 and x is 3, place 1 times in the ring
                    final int count = weight / divider;
                    for (int i = 0; i < count; i++) {
                        final String weightedHost = host + weight;
                        final int hash = getXXHash(weightedHost);
                        ring.put(hash, endpoint);
                    }
                }
            }

            // Returned int values range from -2,147,483,648 to 2,147,483,647, same as java int type
            int getXXHash(String input) {
                final byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
                final long hashBytes = Hashing.murmur3_32_fixed().hashBytes(inputBytes).asInt();
                return (int) (hashBytes >>> 32);
            }

            private int binarySearch(List<Integer> arr, int sz) {
                Collections.sort(arr);
                int lt = arr.get(0);
                int rt = arr.get(arr.size() - 1);
                while (rt > lt + 1) {
                    final int mid = (lt + rt) / 2;
                    if (isPossible(mid, arr, sz)) {
                        rt = mid;
                    } else {
                        lt = mid + 1;
                    }
                }
                return lt;
            }

            private boolean isPossible(int x, List<Integer> arr, int sz) {
                int total = 0;
                for (int w : arr) {
                    total += w / x;
                }

                return sz >= total;
            }
        }
    }
}
