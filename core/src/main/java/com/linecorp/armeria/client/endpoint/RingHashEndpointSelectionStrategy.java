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

import java.nio.charset.StandardCharsets;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;

import net.openhft.hashing.LongHashFunction;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

final class RingHashEndpointSelectionStrategy implements EndpointSelectionStrategy{

    static final RingHashEndpointSelectionStrategy INSTANCE = new RingHashEndpointSelectionStrategy();

    private RingHashEndpointSelectionStrategy() {}

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new RingHashSelector(endpointGroup);
    }
    
    static class RingHashSelector extends AbstractEndpointSelector {
        private volatile WeightedRingEndpoint weightedRingEndpoint;

        RingHashSelector(EndpointGroup endpointGroup) {
            super(endpointGroup);
            endpointGroup.addListener(endpoints -> 
                weightedRingEndpoint = new WeightedRingEndpoint(endpoints), true
            );
        }

        @Override
        public @Nullable Endpoint selectNow(ClientRequestContext ctx) {
            final WeightedRingEndpoint weightedRingEndpoint = this.weightedRingEndpoint;
            return weightedRingEndpoint.select(ctx.endpoint());
        }
        
        private final class WeightedRingEndpoint {
            private final SortedMap<Integer, Endpoint> ring = new ConcurrentSkipListMap<>();

            Endpoint select(Endpoint point) {
                final int key = getXXHash(point.host());
                final SortedMap<Integer, Endpoint> tailMap = ring.tailMap(key);
                return tailMap.isEmpty() ? ring.get(ring.firstKey()) : tailMap.get(tailMap.firstKey());
            }

            WeightedRingEndpoint(Iterable<Endpoint> endpoints) {
                for (Endpoint endpoint : endpoints) {
                    final int weight = endpoint.weight();
                    final String host = endpoint.host();
                    // If weight is 3, place 3 times in the ring, if weight is 1, place once in the ring
                    for (int i = 0; i < weight; i++) {
                        final String weightedHost = host + weight;
                        final int hash = getXXHash(weightedHost);
                        ring.put(hash, endpoint);
                    }
                }
            }

            // Returned int values range from -2,147,483,648 to 2,147,483,647, same as java int type
            int getXXHash(String input) {
                final byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
                final LongHashFunction xxHash = LongHashFunction.xx();
                final long hashBytes = xxHash.hashBytes(inputBytes);
                return (int) (hashBytes >>> 32);
            }
        }
    }
}
