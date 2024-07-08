/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.client.endpoint.EndpointGroupUtil.filter;
import static com.linecorp.armeria.xds.client.endpoint.EndpointGroupUtil.filterByLocality;
import static com.linecorp.armeria.xds.client.endpoint.EndpointUtil.coarseHealth;

import java.util.List;
import java.util.Map;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.xds.client.endpoint.EndpointUtil.CoarseHealth;

import io.envoyproxy.envoy.config.core.v3.Locality;

/**
 * Hosts per partition.
 */
final class UpdateHostsParam {

    private final EndpointGroup hosts;
    private final EndpointGroup healthyHosts;
    private final EndpointGroup degradedHosts;
    private final Map<Locality, EndpointGroup> hostsPerLocality;
    private final Map<Locality, EndpointGroup> healthyHostsPerLocality;
    private final Map<Locality, EndpointGroup> degradedHostsPerLocality;
    private final Map<Locality, Integer> localityWeightsMap;

    UpdateHostsParam(List<Endpoint> endpoints,
                     Map<Locality, List<Endpoint>> endpointsPerLocality,
                     Map<Locality, Integer> localityWeightsMap,
                     EndpointSelectionStrategy strategy) {
        hosts = EndpointGroup.of(strategy, endpoints);
        hostsPerLocality = filterByLocality(endpointsPerLocality, strategy, ignored -> true);
        healthyHosts = filter(endpoints, strategy,
                              endpoint -> coarseHealth(endpoint) == CoarseHealth.HEALTHY);
        healthyHostsPerLocality = filterByLocality(endpointsPerLocality, strategy,
                                                   endpoint -> coarseHealth(endpoint) == CoarseHealth.HEALTHY);
        degradedHosts = filter(endpoints, strategy,
                               endpoint -> coarseHealth(endpoint) == CoarseHealth.DEGRADED);
        degradedHostsPerLocality = filterByLocality(
                endpointsPerLocality, strategy,
                endpoint -> coarseHealth(endpoint) == CoarseHealth.DEGRADED);
        this.localityWeightsMap = localityWeightsMap;
    }

    EndpointGroup hosts() {
        return hosts;
    }

    Map<Locality, EndpointGroup> hostsPerLocality() {
        return hostsPerLocality;
    }

    EndpointGroup healthyHosts() {
        return healthyHosts;
    }

    Map<Locality, EndpointGroup> healthyHostsPerLocality() {
        return healthyHostsPerLocality;
    }

    EndpointGroup degradedHosts() {
        return degradedHosts;
    }

    Map<Locality, EndpointGroup> degradedHostsPerLocality() {
        return degradedHostsPerLocality;
    }

    Map<Locality, Integer> localityWeightsMap() {
        return localityWeightsMap;
    }
}
