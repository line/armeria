/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.endpoint;

import static java.util.Objects.requireNonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.linecorp.armeria.client.Endpoint;

final class WeightedRoundRobinStrategy implements EndpointSelectionStrategy {

    @Override
    @SuppressWarnings("unchecked")
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
    static final class WeightedRoundRobinSelector implements EndpointSelector {
        private final EndpointGroup endpointGroup;
        private final AtomicLong sequence = new AtomicLong();

        WeightedRoundRobinSelector(EndpointGroup endpointGroup) {
            this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
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
        public Endpoint select() {
            long currentSequence = sequence.getAndIncrement();

            List<Endpoint> endpoints = endpointGroup.endpoints();
            if (endpoints.isEmpty()) {
                throw new EndpointGroupException(endpoints + " is empty");
            }
            int minWeight = Integer.MAX_VALUE;
            int maxWeight = Integer.MIN_VALUE;
            int totalWeight = 0;

            // TODO(ide) Build endpointWeights map is too expensive. Add endpoint change notification mechanism.
            Map<Endpoint, AtomicInteger> endpointWeights = new LinkedHashMap<>(endpoints.size());
            for (Endpoint endpoint : endpoints) {
                int weight = endpoint.weight();
                endpointWeights.put(endpoint, new AtomicInteger(weight));
                minWeight = Math.min(minWeight, weight);
                maxWeight = Math.max(maxWeight, weight);
                totalWeight += weight;
            }

            if (minWeight < maxWeight) {
                int mod = (int) (currentSequence % totalWeight);
                for (int i = 0; i < maxWeight; i++) {
                    for (Map.Entry<Endpoint, AtomicInteger> entry : endpointWeights.entrySet()) {
                        AtomicInteger weight = entry.getValue();
                        if (mod == 0 && weight.get() > 0) {
                            return entry.getKey();
                        }
                        if (weight.get() > 0) {
                            weight.decrementAndGet();
                            mod--;
                        }
                    }
                }
            }
            return endpoints.get((int) Math.abs(currentSequence % endpoints.size()));
        }
    }
}
