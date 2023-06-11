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
import java.util.Random;
import java.util.SortedMap;

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

    static class RingHashSelector extends AbstractEndpointSelector {

        @Nullable
        private volatile WeightedRingEndpoint weightedRingEndpoint;

        /**
         * A Ring hash select strategy.
         */
        RingHashSelector(EndpointGroup endpointGroup) {
            super(endpointGroup);
            endpointGroup.addListener(endpoints ->
                                              weightedRingEndpoint = new WeightedRingEndpoint(endpoints), true
            );
        }

        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            final WeightedRingEndpoint weightedRingEndpoint = this.weightedRingEndpoint;
            if (weightedRingEndpoint == null) {
                // 'endpointGroup' has not been initialized yet.
                return null;
            }

            return weightedRingEndpoint.select(ctx.endpoint());
        }

        private final class WeightedRingEndpoint {
            private final Int2ObjectSortedMap<Endpoint> ring = new Int2ObjectAVLTreeMap<>();
            private final Iterable<Endpoint> endpoints;

            Endpoint select(Endpoint point) {
                final Random random = new Random();
                final String randomString = String.valueOf(random.nextInt());
                final int key = getXXHash(randomString);
                final SortedMap<Integer, Endpoint> tailMap = ring.tailMap(key);
                return tailMap.isEmpty() ? ring.get(ring.firstKey()) : tailMap.get(tailMap.firstKey());
            }

            WeightedRingEndpoint(List<Endpoint> endpoints) {
                // prepare immutable endpoints
                this.endpoints = endpoints.stream()
                                        .filter(e -> e.weight() > 0) // only process endpoint with weight > 0
                                        .sorted(Comparator.comparing(Endpoint::weight)
                                                          .thenComparing(Endpoint::host)
                                                          .thenComparingInt(Endpoint::port))
                                        .collect(toImmutableList());

                // The GCD is properly sized so that it does not exceed the size of the ring
                final int gcd = findGcdInEndpoints(this.endpoints);
                final int numberOfEndpointInTheRing = calculateNumberOfEndpointInTheRing(this.endpoints, gcd);

                final int sizeOfRing = getSize(endpoints);
                if (sizeOfRing >= numberOfEndpointInTheRing) {
                    for (Endpoint endpoint : this.endpoints) {
                        final int weight = endpoint.weight();
                        final String host = endpoint.host();
                        final String port = String.valueOf(endpoint.port());
                        // If weight is 3 and gcd is 1, place 3 times in the ring
                        // if weight is 1 and gcd is 1, place once in the ring
                        // If weight is 3 and gcd is 3, place 1 times in the ring
                        final int count = weight / gcd;
                        for (int i = 0; i < count; i++) {
                            final String weightedHost = host + port + weight;
                            final int hash = getXXHash(weightedHost);
                            ring.put(hash, endpoint);
                        }
                    }
                } else {
                    // When the size of the GCD is too small and exceeds the size of the ring,
                    // using binary search for find x
                    // where Σ (w[i] / x) ≤ ring_size, w[i] is endpoint's weight at index i
                    final List<Integer> arr = new ArrayList<>();
                    for (Endpoint endpoint : this.endpoints) {
                        final int weight = endpoint.weight();
                        arr.add(weight);
                    }
                    final int divider = binarySearch(arr, sizeOfRing);
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
            }

            private int getSize(Iterable<Endpoint> endpoints) {
                int count = 0;
                for (Endpoint endpoint : endpoints) {
                    count++;
                }
                return count;
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

            int calculateNumberOfEndpointInTheRing(Iterable<Endpoint> endpoints, int gcd) {
                int numberOfEndpointInTheRing = 0;
                for (final Endpoint endpoint : endpoints) {
                    final int weight = endpoint.weight();
                    final int count = weight / gcd;
                    numberOfEndpointInTheRing += count;
                }
                return numberOfEndpointInTheRing;
            }

            int findGcdInEndpoints(Iterable<Endpoint> endpoints) {
                int gcd = -1;
                for (Endpoint endpoint : endpoints) {
                    final int weight = endpoint.weight();
                    // initialize gcd
                    if (gcd == -1) {
                        gcd = weight;
                        continue;
                    }

                    gcd = gcd(gcd, weight);
                }
                return gcd;
            }

            int gcd(int a, int b) {
                while (b != 0) {
                    final int temp = b;
                    b = a % b;
                    a = temp;
                }
                return a;
            }

            // Returned int values range from -2,147,483,648 to 2,147,483,647, same as java int type
            int getXXHash(String input) {
                final byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
                final long hashBytes = Hashing.murmur3_32_fixed().hashBytes(inputBytes).asInt();
                return (int) (hashBytes >>> 32);
            }
        }
    }
}
