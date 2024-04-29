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

import static com.linecorp.armeria.xds.client.endpoint.EndpointGroupUtil.filterByLocality;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;

import io.envoyproxy.envoy.config.core.v3.Locality;

class SubsetPrioritySetBuilder {

    private final Map<Integer, Set<Endpoint>> rawHostsSet = new HashMap<>();
    private final PrioritySet origPrioritySet;
    private final boolean scaleLocalityWeight;

    SubsetPrioritySetBuilder(PrioritySet origPrioritySet, boolean scaleLocalityWeight) {
        this.origPrioritySet = origPrioritySet;
        this.scaleLocalityWeight = scaleLocalityWeight;
    }

    void pushHost(int priority, Endpoint host) {
        rawHostsSet.computeIfAbsent(priority, ignored -> new HashSet<>())
                   .add(host);
    }

    UpdateHostsParam finalize(int priority) {
        final HostSet origHostSet = origPrioritySet.hostSets().get(priority);
        final Set<Endpoint> newHostSet = rawHostsSet.getOrDefault(priority, Collections.emptySet());
        assert origHostSet != null;
        final EndpointGroup hosts =
                EndpointGroupUtil.filter(origHostSet.hostsEndpointGroup(), newHostSet::contains);
        final EndpointGroup healthyHosts =
                EndpointGroupUtil.filter(origHostSet.healthyHostsEndpointGroup(), newHostSet::contains);
        final EndpointGroup degradedHosts =
                EndpointGroupUtil.filter(origHostSet.degradedHostsEndpointGroup(), newHostSet::contains);
        final Map<Locality, EndpointGroup> hostsPerLocality =
                filterByLocality(origHostSet.endpointGroupPerLocality(), newHostSet::contains);
        final Map<Locality, EndpointGroup> healthyHostsPerLocality =
                filterByLocality(origHostSet.healthyEndpointGroupPerLocality(), newHostSet::contains);
        final Map<Locality, EndpointGroup> degradedHostsPerLocality =
                filterByLocality(origHostSet.degradedEndpointGroupPerLocality(), newHostSet::contains);

        final Map<Locality, Integer> localityWeightsMap =
                determineLocalityWeights(hostsPerLocality, origHostSet);
        return new UpdateHostsParam(hosts, healthyHosts, degradedHosts,
                                    hostsPerLocality, healthyHostsPerLocality,
                                    degradedHostsPerLocality, localityWeightsMap);
    }

    Map<Locality, Integer> determineLocalityWeights(Map<Locality, EndpointGroup> hostsPerLocality,
                                                    HostSet origHostSet) {
        final Map<Locality, Integer> localityWeightsMap = origHostSet.localityWeightsMap();
        if (!scaleLocalityWeight) {
            return localityWeightsMap;
        }
        final Map<Locality, EndpointGroup> origHostsPerLocality = origHostSet.endpointGroupPerLocality();
        final ImmutableMap.Builder<Locality, Integer> scaledLocalityWeightsMap = ImmutableMap.builder();
        for (Entry<Locality, Integer> entry : localityWeightsMap.entrySet()) {
            final float scale = 1.0f * hostsPerLocality.get(entry.getKey()).endpoints().size() /
                                origHostsPerLocality.get(entry.getKey()).endpoints().size();
            scaledLocalityWeightsMap.put(entry.getKey(), Math.round(scale * entry.getValue()));
        }
        return scaledLocalityWeightsMap.build();
    }
}
