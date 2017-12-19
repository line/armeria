/*
 * Copyright 2017 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;

final class RoundRobinStrategy implements EndpointSelectionStrategy {

    @Override
    @SuppressWarnings("unchecked")
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new RoundRobinSelector(endpointGroup);
    }

    /**
     * A round robin select strategy.
     *
     * <p>For example, with node a, b and c, then select result is abc abc ...
     */
    static class RoundRobinSelector implements EndpointSelector {
        private final EndpointGroup endpointGroup;

        private final AtomicInteger sequence = new AtomicInteger();

        RoundRobinSelector(EndpointGroup endpointGroup) {
            this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
        }

        @Override
        public EndpointGroup group() {
            return endpointGroup;
        }

        @Override
        public EndpointSelectionStrategy strategy() {
            return ROUND_ROBIN;
        }

        @Override
        public Endpoint select(ClientRequestContext ctx) {

            List<Endpoint> endpoints = endpointGroup.endpoints();
            int currentSequence = sequence.getAndIncrement();

            if (endpoints.isEmpty()) {
                throw new EndpointGroupException(endpointGroup + " is empty");
            }
            return endpoints.get(Math.abs(currentSequence % endpoints.size()));
        }
    }
}
