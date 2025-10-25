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

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DefaultEndpointSelector.LoadBalancerFactory;
import com.linecorp.armeria.common.loadbalancer.LoadBalancer;

enum RoundRobinStrategy
        implements EndpointSelectionStrategy,
                   LoadBalancerFactory<LoadBalancer<Endpoint, ClientRequestContext>> {

    INSTANCE;

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new DefaultEndpointSelector<>(endpointGroup, this);
    }

    @Override
    public LoadBalancer<Endpoint, ClientRequestContext> newLoadBalancer(
            @Nullable LoadBalancer<Endpoint, ClientRequestContext> oldLoadBalancer, List<Endpoint> candidates) {
        return unsafeCast(LoadBalancer.ofRoundRobin(candidates));
    }
}
