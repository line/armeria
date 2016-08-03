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
package com.linecorp.armeria.client.routing;

import com.linecorp.armeria.client.Endpoint;

/**
 * {@link Endpoint} selection strategy that creates a {@link EndpointSelector}.
 */
@FunctionalInterface
public interface EndpointSelectionStrategy {

    /**
     * Weighted round-robin strategy.
     */
    EndpointSelectionStrategy WEIGHTED_ROUND_ROBIN = new WeightedRoundRobinStrategy();

    /**
     * Creates a new {@link EndpointSelector} that selects an {@link Endpoint} from the specified
     * {@link EndpointGroup}.
     */
    EndpointSelector newSelector(EndpointGroup endpointGroup);
}
