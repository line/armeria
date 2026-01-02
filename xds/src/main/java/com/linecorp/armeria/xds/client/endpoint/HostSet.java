/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.Map;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.loadbalancer.SimpleLoadBalancer;

import io.envoyproxy.envoy.config.core.v3.Locality;

/**
 * Represents a set of hosts for xDS load balancing.
 */
@UnstableApi
public interface HostSet {

    /**
     * Returns the endpoint group for all hosts.
     */
    EndpointGroup hostsEndpointGroup();

    /**
     * Returns a map of endpoint groups per locality.
     */
    Map<Locality, EndpointGroup> endpointGroupPerLocality();

    /**
     * Returns the endpoint group for healthy hosts.
     */
    EndpointGroup healthyHostsEndpointGroup();

    /**
     * Returns a map of healthy endpoint groups per locality.
     */
    Map<Locality, EndpointGroup> healthyEndpointGroupPerLocality();

    /**
     * Returns the endpoint group for degraded hosts.
     */
    EndpointGroup degradedHostsEndpointGroup();

    /**
     * Returns a map of degraded endpoint groups per locality.
     */
    Map<Locality, EndpointGroup> degradedEndpointGroupPerLocality();

    /**
     * Returns the load balancer for selecting degraded localities.
     */
    SimpleLoadBalancer<WeightedLocality> degradedLocalitySelector();

    /**
     * Returns the load balancer for selecting healthy localities.
     */
    SimpleLoadBalancer<WeightedLocality> healthyLocalitySelector();

    /**
     * Returns a map of weights per locality.
     */
    Map<Locality, Integer> localityWeights();
}
