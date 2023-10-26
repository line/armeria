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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;

final class RoundRobinStrategy implements EndpointSelectionStrategy {

    static final RoundRobinStrategy INSTANCE = new RoundRobinStrategy();

    private RoundRobinStrategy() {}

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new RoundRobinSelector(endpointGroup);
    }

    /**
     * A round robin select strategy.
     *
     * <p>For example, with node a, b and c, then select result is abc abc ...
     */
    static class RoundRobinSelector extends AbstractEndpointSelector {
        private final AtomicInteger sequence = new AtomicInteger();

        RoundRobinSelector(EndpointGroup endpointGroup) {
            super(endpointGroup);
            initialize();
        }

        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            final List<Endpoint> endpoints = group().endpoints();
            if (endpoints.isEmpty()) {
                return null;
            }
            final int currentSequence = sequence.getAndIncrement();
            return endpoints.get(Math.abs(currentSequence % endpoints.size()));
        }
    }
}
